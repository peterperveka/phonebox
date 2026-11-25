using Microsoft.Extensions.Logging.Abstractions;
using PhoneBox.Agent;
using Xunit;

/// <summary>The one pending unlock request: ends by the phone's answer, its cancel, a timeout, a closed connection or a newer request.</summary>
public sealed class UnlockStoreTests : IDisposable
{
    private static readonly TimeSpan Long = TimeSpan.FromSeconds(30);
    private readonly string _dir = Directory.CreateTempSubdirectory("pb-unlock-").FullName;
    private readonly PushStore _events;
    private readonly UnlockStore _store;

    public UnlockStoreTests()
    {
        _events = new PushStore(NullLogger<PushStore>.Instance, Path.Combine(_dir, "events.json"));
        _store = new UnlockStore(_events, NullLogger<UnlockStore>.Instance);
    }

    public void Dispose()
    {
        _events.Dispose();
        Directory.Delete(_dir, recursive: true);
    }

    private async Task<string> WaitForPending()
    {
        var until = DateTime.UtcNow.AddSeconds(5);
        while (_store.Pending() is null)
        {
            Assert.True(DateTime.UtcNow < until, "request never became pending");
            await Task.Yield();
        }
        return _store.Pending()!.Id;
    }

    [Fact]
    public async Task AnswerCompletesTheRequest()
    {
        var task = _store.RequestAsync("chal", Long, CancellationToken.None);
        var id = await WaitForPending();
        Assert.Equal("chal", _store.Pending()!.Challenge);
        Assert.True(_store.Answer(id, "sig"));
        Assert.Equal("sig", await task.WaitAsync(TimeSpan.FromSeconds(5)));
        Assert.Null(_store.Pending());
    }

    [Fact]
    public async Task PhoneCancelReturnsNull()
    {
        var task = _store.RequestAsync("chal", Long, CancellationToken.None);
        var id = await WaitForPending();
        Assert.True(_store.Answer(id, null));
        Assert.Null(await task.WaitAsync(TimeSpan.FromSeconds(5)));
        Assert.Null(_store.Pending());
    }

    [Fact]
    public async Task TimeoutReturnsNull()
    {
        var task = _store.RequestAsync("chal", TimeSpan.FromMilliseconds(50), CancellationToken.None);
        Assert.Null(await task.WaitAsync(TimeSpan.FromSeconds(5)));
        Assert.Null(_store.Pending());
    }

    [Fact]
    public async Task ClosedConnectionEndsTheRequest()
    {
        using var cts = new CancellationTokenSource();
        var task = _store.RequestAsync("chal", Long, cts.Token);
        await WaitForPending();
        cts.Cancel();
        Assert.Null(await task.WaitAsync(TimeSpan.FromSeconds(5)));
        Assert.Null(_store.Pending());
    }

    [Fact]
    public async Task AnswerAfterCancelIsIgnored()
    {
        using var cts = new CancellationTokenSource();
        var task = _store.RequestAsync("chal", Long, cts.Token);
        var id = await WaitForPending();
        cts.Cancel();
        await task.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.False(_store.Answer(id, "sig"));
    }

    [Fact]
    public async Task NewRequestEndsTheOldOne()
    {
        var first = _store.RequestAsync("one", Long, CancellationToken.None);
        var firstId = await WaitForPending();
        var second = _store.RequestAsync("two", Long, CancellationToken.None);
        Assert.Null(await first.WaitAsync(TimeSpan.FromSeconds(5)));
        Assert.Equal("two", _store.Pending()!.Challenge);
        Assert.False(_store.Answer(firstId, "sig"));
        Assert.True(_store.Answer(_store.Pending()!.Id, "sig2"));
        Assert.Equal("sig2", await second.WaitAsync(TimeSpan.FromSeconds(5)));
    }

    [Fact]
    public void UnlockIsOffByDefault() => Assert.False(new AgentConfig().Unlock.Enabled);
}
