using System.Buffers;
using System.Text;
using System.Text.Json;

namespace Slipstream.Core.Control;

/// <summary>
/// Spec §6 framing: one UTF-8 JSON object per newline-terminated line.
/// Malformed lines are skipped, never fatal — a peer on a newer protocol
/// version must degrade rather than disconnect.
/// </summary>
public sealed class JsonLineCodec(Stream stream)
{
    public const int MaxLineBytes = 1_048_576;

    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private readonly ArrayBufferWriter<byte> _line = new(4096);

    public async Task WriteAsync(ControlMessage message, CancellationToken cancellationToken)
    {
        var bytes = JsonSerializer.SerializeToUtf8Bytes(message, ControlMessage.Json);

        if (bytes.Length + 1 > MaxLineBytes)
            throw new ControlProtocolException($"Outgoing message of {bytes.Length} bytes exceeds the line limit.");

        await _writeLock.WaitAsync(cancellationToken);
        try
        {
            await stream.WriteAsync(bytes, cancellationToken);
            await stream.WriteAsync("\n"u8.ToArray(), cancellationToken);
            await stream.FlushAsync(cancellationToken);
        }
        finally
        {
            _writeLock.Release();
        }
    }

    /// <summary>Returns null at end of stream.</summary>
    public async Task<ControlMessage?> ReadAsync(CancellationToken cancellationToken)
    {
        while (true)
        {
            var line = await ReadLineAsync(cancellationToken);
            if (line is null) return null;
            if (line.Length == 0) continue;

            var message = TryParse(line);
            if (message is not null) return message;
            // Malformed or type-less: skip and keep reading.
        }
    }

    private async Task<byte[]?> ReadLineAsync(CancellationToken cancellationToken)
    {
        _line.Clear();
        var single = new byte[1];

        while (true)
        {
            var read = await stream.ReadAsync(single, cancellationToken);
            if (read == 0) return _line.WrittenCount == 0 ? null : _line.WrittenSpan.ToArray();

            if (single[0] == (byte)'\n') return _line.WrittenSpan.ToArray();
            if (single[0] == (byte)'\r') continue;

            if (_line.WrittenCount >= MaxLineBytes)
                throw new ControlProtocolException($"Incoming line exceeded {MaxLineBytes} bytes.");

            _line.Write(single);
        }
    }

    private static ControlMessage? TryParse(byte[] line)
    {
        try
        {
            var message = JsonSerializer.Deserialize<ControlMessage>(line, ControlMessage.Json);
            return string.IsNullOrWhiteSpace(message?.Type) ? null : message;
        }
        catch (JsonException)
        {
            return null;
        }
    }
}
