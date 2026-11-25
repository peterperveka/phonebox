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
        public int Checks;
        public string? GraphicalSessionId() { Checks++; return "c1"; }
        public bool IsLocked(string id) => Locked;
        public bool Unlock(string id) { UnlockCalls++; return true; }
    }

    private readonly string _path = Path.Combine(Path.GetTempPath(), $"phonebox-unlock-{Guid.NewGuid():N}.json");
    private readonly string _keyFile = Path.Combine(Path.GetTempPath(), $"phonebox-unlock-{Guid.NewGuid():N}.pub");
    private readonly ECDsa _key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
    private readonly Clock _clock = new();
    private readonly FakeSession _session = new() { Locked = true };

    public void Dispose() { _key.Dispose(); try { File.Delete(_path); File.Delete(_path + ".bak"); File.Delete(_keyFile); } catch { } }

    private UnlockService Make(bool enabled = true, bool enrolled = true, bool locked = true, bool supportedOs = true,
        bool phone = true, ECDsa? configKey = null)
    {
        var pk = enrolled ? $"\"{Convert.ToBase64String((configKey ?? _key).ExportSubjectPublicKeyInfo())}\"" : "null";
        File.WriteAllText(_path, "{\"unlock\":{\"enabled\":" + (enabled ? "true" : "false") + ",\"publicKey\":" + pk + "}}");
        _session.Locked = locked;
        var svc = new UnlockService(new ConfigStore(_path), _session, new PushStore(NullLogger<PushStore>.Instance, null),
            NullLogger<UnlockService>.Instance, _clock, () => supportedOs, () => phone, _keyFile);
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

    [Fact]
    public void UnsupportedOsStaysUnlockedAndInactive()
    {
        var svc = Make(supportedOs: false);
        Assert.False(svc.Locked);
        Assert.Null(svc.ScreenChallenge());
        Assert.Equal(0, _session.Checks);
    }

    [Fact]
    public void NoLockCheckWhilePhoneIsAway()
    {
        var svc = Make(phone: false);
        Assert.False(svc.Locked);
        Assert.Null(svc.ScreenChallenge());
        Assert.Equal(0, _session.Checks);
    }

    [Fact]
    public void LockReturnsOnTheTickAfterThePhoneComesBack()
    {
        var phone = false;
        File.WriteAllText(_path, "{\"unlock\":{\"enabled\":true,\"publicKey\":\"" + Convert.ToBase64String(_key.ExportSubjectPublicKeyInfo()) + "\"}}");
        var svc = new UnlockService(new ConfigStore(_path), _session, new PushStore(NullLogger<PushStore>.Instance, null),
            NullLogger<UnlockService>.Instance, _clock, () => true, () => phone, _keyFile);
        svc.Refresh();
        Assert.False(svc.Locked);
        phone = true;
        svc.Refresh();
        Assert.True(svc.Locked);
        phone = false;
        svc.Refresh();
        Assert.False(svc.Locked);
    }

    [Fact]
    public void RootKeyFileWinsOverConfigKey()
    {
        using var other = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        File.WriteAllText(_keyFile, Convert.ToBase64String(_key.ExportSubjectPublicKeyInfo()) + "\n");
        var svc = Make(configKey: other);
        var c = svc.ScreenChallenge()!;
        var byOther = Convert.ToBase64String(other.SignData(Encoding.UTF8.GetBytes(c), HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence));
        Assert.False(svc.UnlockScreen(c, byOther));
        c = svc.ScreenChallenge()!;
        Assert.True(svc.UnlockScreen(c, Sign(c)));
    }

    [Fact]
    public void ConfigKeyUsedWithoutRootKeyFile()
    {
        Assert.False(File.Exists(_keyFile));
        var svc = Make();
        var c = svc.ScreenChallenge()!;
        Assert.True(svc.UnlockScreen(c, Sign(c)));
    }

    [Fact]
    public void InvalidRootKeyFileRefusesWithoutFallback()
    {
        File.WriteAllText(_keyFile, "not a key\n");
        var svc = Make();
        var c = svc.ScreenChallenge()!;
        Assert.False(svc.UnlockScreen(c, Sign(c)));
        Assert.Equal(0, _session.UnlockCalls);
    }

    [Fact]
    public void EnrollOnlyWhileEnabled()
    {
        Assert.False(UnlockService.CanEnroll(new UnlockConfig { Enabled = false }));
        Assert.True(UnlockService.CanEnroll(new UnlockConfig { Enabled = true }));
    }

    [Fact]
    public void LockedForCountsFromWhenTheLockWasSeen()
    {
        var svc = Make(locked: false);
        Assert.Null(svc.LockedForMs());
        _session.Locked = true;
        svc.Refresh();
        _clock.Now += 4_000;
        Assert.Equal(4_000, svc.LockedForMs());
        _session.Locked = false;
        svc.Refresh();
        Assert.Null(svc.LockedForMs());
    }
}
