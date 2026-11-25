using System.Text.Json;

namespace PhoneBox.Agent;

/// <summary>
/// Tracks whether the GNOME screen is locked and lets the phone unlock it with a signed, single-use
/// <c>screen</c> challenge. Does nothing unless the feature is enabled and enrolled on Linux.
/// </summary>
public sealed class UnlockService(ConfigStore cfg, ILoginSession session, PushStore events, ILogger<UnlockService> log, TimeProvider clock) : BackgroundService
{
    private const long ChallengeLifetimeMs = 60_000;

    private readonly Lock _lock = new();
    private string? _issued;
    private long _issuedExpires;
    private volatile bool _locked;

    /// <summary>True while the screen is locked; always false unless Linux and the feature is enabled.</summary>
    public bool Locked => _locked;

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(1));
        do
        {
            try { Refresh(); }
            catch (Exception ex) { log.LogDebug(ex, "Screen lock check failed"); }
        }
        while (await timer.WaitForNextTickAsync(stoppingToken));
    }

    /// <summary>One lock check; emits an <c>unlock</c> event when the state changed.</summary>
    internal void Refresh()
    {
        var now = false;
        if (OperatingSystem.IsLinux() && cfg.Get().Unlock.Enabled)
        {
            var id = session.GraphicalSessionId();
            now = id is not null && session.IsLocked(id);
        }
        if (now == _locked) return;
        _locked = now;
        if (!now) lock (_lock) _issued = null;
        events.Emit("unlock", JsonSerializer.SerializeToElement(new { locked = now }));
    }

    private static bool Enrolled(UnlockConfig u) => u.Enabled && !string.IsNullOrEmpty(u.PublicKey);

    /// <summary>The challenge the phone must sign to unlock the screen, or null if there is nothing to unlock.</summary>
    public string? ScreenChallenge()
    {
        if (!_locked || !Enrolled(cfg.Get().Unlock)) return null;
        var now = clock.GetUtcNow().ToUnixTimeMilliseconds();
        lock (_lock)
        {
            if (_issued is null || now >= _issuedExpires)
            {
                _issuedExpires = now + ChallengeLifetimeMs;
                _issued = UnlockChallenge.Create("screen", Environment.UserName, Environment.MachineName, "-", _issuedExpires);
            }
            return _issued;
        }
    }

    /// <summary>Unlocks the screen if the signature is valid for the challenge issued here. A challenge works once.</summary>
    public bool UnlockScreen(string challenge, string signatureB64)
    {
        string? issued;
        long expires;
        lock (_lock)
        {
            issued = _issued;
            expires = _issuedExpires;
            _issued = null;
        }
        var u = cfg.Get().Unlock;
        if (issued is null || challenge != issued || !_locked || !Enrolled(u)) return false;
        if (clock.GetUtcNow().ToUnixTimeMilliseconds() >= expires) return false;
        if (!UnlockChallenge.Verify(u.PublicKey!, challenge, signatureB64)) return false;
        var id = session.GraphicalSessionId();
        if (id is null || !session.IsLocked(id)) return false;
        var ok = session.Unlock(id);
        log.LogInformation("Screen unlocked from the phone: {Ok}", ok);
        return ok;
    }
}
