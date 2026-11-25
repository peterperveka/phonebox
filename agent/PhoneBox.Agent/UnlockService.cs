using System.Text.Json;

namespace PhoneBox.Agent;

/// <summary>
/// Tracks whether the GNOME screen is locked and lets the phone unlock it with a signed, single-use
/// <c>screen</c> challenge. Does nothing unless the feature is enabled and enrolled on Linux.
/// </summary>
public sealed class UnlockService : BackgroundService
{
    private const long ChallengeLifetimeMs = 60_000;

    private readonly ConfigStore cfg;
    private readonly ILoginSession session;
    private readonly PushStore events;
    private readonly ILogger<UnlockService> log;
    private readonly TimeProvider clock;
    private readonly Func<bool> supportedOs;
    private readonly Func<bool> phoneConnected;
    private readonly string keyFile;

    public UnlockService(ConfigStore cfg, ILoginSession session, PushStore events, ILogger<UnlockService> log, TimeProvider clock)
        : this(cfg, session, events, log, clock, OperatingSystem.IsLinux, () => ApprovalStore.PhoneConnected,
            Path.Combine("/etc/phonebox", Environment.UserName + ".pub")) { }

    /// <summary>Test seam: the OS gate, the phone-connected check and the root-owned key file the helper writes.</summary>
    internal UnlockService(ConfigStore cfg, ILoginSession session, PushStore events, ILogger<UnlockService> log, TimeProvider clock,
        Func<bool> supportedOs, Func<bool> phoneConnected, string keyFile)
    {
        this.cfg = cfg;
        this.session = session;
        this.events = events;
        this.log = log;
        this.clock = clock;
        this.supportedOs = supportedOs;
        this.phoneConnected = phoneConnected;
        this.keyFile = keyFile;
    }

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

    /// <summary>
    /// One lock check; emits an <c>unlock</c> event when the state changed. No loginctl calls while the phone is away
    /// (Locked stays false then; the first tick after it returns refreshes).
    /// </summary>
    internal void Refresh()
    {
        var now = false;
        if (supportedOs() && cfg.Get().Unlock.Enabled && phoneConnected())
        {
            var id = session.GraphicalSessionId();
            now = id is not null && session.IsLocked(id);
        }
        if (now == _locked) return;
        _locked = now;
        if (!now) lock (_lock) _issued = null;
        events.Emit("unlock", JsonSerializer.SerializeToElement(new { locked = now }));
    }

    /// <summary>The phone may (re)enroll a key only while the feature is on.</summary>
    public static bool CanEnroll(UnlockConfig u) => u.Enabled;

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
        var key = ScreenKey(u);
        if (key is null || !UnlockChallenge.Verify(key, challenge, signatureB64)) return false;
        var id = session.GraphicalSessionId();
        if (id is null || !session.IsLocked(id)) return false;
        var ok = session.Unlock(id);
        log.LogInformation("Screen unlocked from the phone: {Ok}", ok);
        return ok;
    }

    /// <summary>
    /// The key screen signatures must match: the root-owned key the user confirmed with <c>phonebox-unlock enroll</c>
    /// when that file exists, else the key the phone enrolled. A missing or unreadable file falls back; a file with
    /// invalid content refuses (null) rather than falling back.
    /// </summary>
    private string? ScreenKey(UnlockConfig u)
    {
        string text;
        try { text = File.ReadAllText(keyFile).Trim(); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { return u.PublicKey; }
        return UnlockChallenge.CheckCode(text) is null ? null : text;
    }
}
