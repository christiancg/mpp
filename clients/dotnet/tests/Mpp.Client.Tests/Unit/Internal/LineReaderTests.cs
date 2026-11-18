using System.IO.Pipelines;
using System.Threading;
using System.Threading.Tasks;
using Mpp.Client.Internal;
using Xunit;

namespace Mpp.Client.Tests.Unit.Internal;

public sealed class LineReaderTests
{
    private readonly Pipe _pipe = new();

    private LineReader Reader(long max = 1024) => new(_pipe.Reader.AsStream(), max);

    private async Task Write(params byte[][] chunks)
    {
        foreach (var chunk in chunks)
        {
            await _pipe.Writer.WriteAsync(chunk);
            await _pipe.Writer.FlushAsync();
        }
    }

    [Fact]
    public async Task ALineSplitAcrossReadsIsJoined()
    {
        using var reader = Reader();
        await Write([.. "{\"a\""u8], [.. ":1}\r\n"u8]);
        Assert.Equal(new LineEvent(LineKind.Text, "{\"a\":1}"), await reader.ReadLineAsync(CancellationToken.None));
    }

    [Fact]
    public async Task SeveralLinesInOneReadAreSeparated()
    {
        using var reader = Reader();
        await Write([.. "a\nb\n\nc\n"u8]);
        Assert.Equal("a", (await reader.ReadLineAsync(CancellationToken.None)).Text);
        Assert.Equal("b", (await reader.ReadLineAsync(CancellationToken.None)).Text);
        Assert.Equal(string.Empty, (await reader.ReadLineAsync(CancellationToken.None)).Text);
        Assert.Equal("c", (await reader.ReadLineAsync(CancellationToken.None)).Text);
    }

    [Fact]
    public async Task AMultiByteCharacterSplitAcrossReadsDecodes()
    {
        using var reader = Reader();
        var bytes = "ñandú\n"u8.ToArray();
        await Write(bytes[..1], bytes[1..3], bytes[3..]);
        Assert.Equal("ñandú", (await reader.ReadLineAsync(CancellationToken.None)).Text);
    }

    [Fact]
    public async Task AnOversizedLineIsReportedOnceAndTheNextLineIsRead()
    {
        using var reader = Reader(8);
        await Write([.. "0123456789"u8], [.. "abc\nok\n"u8]);
        Assert.Equal(LineKind.TooLarge, (await reader.ReadLineAsync(CancellationToken.None)).Kind);
        Assert.Equal("ok", (await reader.ReadLineAsync(CancellationToken.None)).Text);
    }

    [Fact]
    public async Task TheEndOfTheStreamIsReported()
    {
        using var reader = Reader();
        await _pipe.Writer.CompleteAsync();
        Assert.Equal(LineKind.End, (await reader.ReadLineAsync(CancellationToken.None)).Kind);
    }
}
