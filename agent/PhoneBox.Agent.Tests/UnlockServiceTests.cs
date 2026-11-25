using System.Security.Cryptography;
using System.Text;
using Microsoft.Extensions.Logging.Abstractions;
using PhoneBox.Agent;
using Xunit;

public sealed class UnlockServiceTests : IDisposable
{
    private sealed class Clock : TimeProvider
    {
        public long Now = 1_000_000;
        public override DateTimeOffset GetUtcNow() => DateTimeOffset.FromUnixTimeMilliseconds(Now);
    }

    private sealed class FakeSession : ILoginSession
    {
        public bool Locked;
        public int UnlockCalls;
        public string? GraphicalSessionId() => "c1";
        public bool IsLocked(string id) => Locked;
        public bool Unlock(string id) { UnlockCalls++; return true; }
    }

    private readonly string _path = Path.Combine(Path.GetTempPath(), $"phonebox-unlock-{Guid.NewGuid():N}.json");
    private readonly ECDsa _key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
    private readonly Clock _clock = new();
    private readonly FakeSession _session = new() { Locked = true };

    public void Dispose() { _key.Dispose(); try { File.Delete(_path); File.Delete(_path + ".bak"); } catch { } }

    private UnlockService Make(bool enabled = true, bool enrolled = true, bool locked = true)
    {
        var pk = enrolled ? $"\"{Convert.ToBase64String(_key.ExportSubjectPublicKeyInfo())}\"" : "null";
        File.WriteAllText(_path, "{\"unlock\":{\"enabled\":" + (enabled ? "true" : "false") + ",\"publicKey\":" + pk + "}}");
        _session.Locked = locked;
        var svc = new UnlockService(new ConfigStore(_path), _session, new PushStore(NullLogger<PushStore>.Instance, null),
            NullLogger<UnlockService>.Instance, _clock);
        svc.Refresh();
        return svc;
    }

    private string Sign(string text) => Convert.ToBase64String(
        _key.SignData(Encoding.UTF8.GetBytes(text), HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence));

    [Fact]
    public void NoChallengeWhenDisabledOrNotEnrolledOrUnlocked()
    {
        Assert.Null(Make(enabled: false).ScreenChallenge());
        Assert.Null(Make(enrolled: false).ScreenChallenge());
        Assert.Null(Make(locked: false).ScreenChallenge());
        Assert.False(Make(enabled: false).Locked);
    }

    [Fact]
    public void ChallengeIsScreenPurposeAndStableUntilExpiry()
    {
        var svc = Make();
        var first = svc.ScreenChallenge()!;
        var p = UnlockChallenge.Parse(first);
        Assert.NotNull(p);
        Assert.Equal("screen", p.Purpose);
        Assert.Equal("-", p.Command);
        Assert.Equal(Environment.UserName, p.User);
        Assert.Equal(Environment.MachineName, p.Host);
        _clock.Now += 59_000;
        Assert.Equal(first, svc.ScreenChallenge());
        _clock.Now += 2_000;
        var next = svc.ScreenChallenge();
        Assert.NotNull(next);
        Assert.NotEqual(first, next);
    }

    [Fact]
    public void ValidSignatureUnlocks()
    {
        var svc = Make();
        var c = svc.ScreenChallenge()!;
        Assert.True(svc.UnlockScreen(c, Sign(c)));
        Assert.Equal(1, _session.UnlockCalls);
    }

    [Fact]
    public void ScreenSignatureWorksOnlyOnce()
    {
        var svc = Make();
        var c = svc.ScreenChallenge()!;
        var sig = Sign(c);
        Assert.True(svc.UnlockScreen(c, sig));
        Assert.False(svc.UnlockScreen(c, sig));
        Assert.Equal(1, _session.UnlockCalls);
    }

    [Fact]
    public void SudoChallengeIsRefused()
    {
        var svc = Make();
        svc.ScreenChallenge();
        var sudo = UnlockChallenge.Create("sudo", Environment.UserName, Environment.MachineName, "ls", _clock.Now + 60_000);
        Assert.False(svc.UnlockScreen(sudo, Sign(sudo)));
        Assert.Equal(0, _session.UnlockCalls);
    }

    [Fact]
    public void ExpiredChallengeIsRefused()
    {
        var svc = Make();
        var c = svc.ScreenChallenge()!;
        _clock.Now += 61_000;
        Assert.False(svc.UnlockScreen(c, Sign(c)));
        Assert.Equal(0, _session.UnlockCalls);
    }

    [Fact]
    public void UnlockWhenNotLockedDoesNothing()
    {
        var svc = Make();
        var c = svc.ScreenChallenge()!;
        _session.Locked = false;
        svc.Refresh();
        Assert.False(svc.UnlockScreen(c, Sign(c)));
        Assert.Equal(0, _session.UnlockCalls);
    }
}
