using System.Globalization;
using System.Security.Cryptography;
using System.Text;

namespace PhoneBox.Agent;

/// <summary>The fields of a parsed unlock challenge.</summary>
public sealed record UnlockChallengeParts(string Purpose, string User, string Host, string Command, string Nonce, long ExpiresAt);

/// <summary>
/// One-time challenge text that the phone signs with its fingerprint-protected key
/// (<c>phonebox-unlock-v1|purpose|user|host|command|nonce|expiresAtMs</c>), plus signature
/// verification and the short check code shown when pairing.
/// </summary>
public static class UnlockChallenge
{
    public const string Prefix = "phonebox-unlock-v1";
    private const int MaxCommand = 120;

    /// <summary>Builds a challenge with a fresh random 256-bit nonce.</summary>
    public static string Create(string purpose, string user, string host, string command, long expiresAtMs)
    {
        var nonce = Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(32));
        return string.Join('|', Prefix, purpose, user, host, command, nonce,
            expiresAtMs.ToString(CultureInfo.InvariantCulture));
    }

    /// <summary>Parses a challenge, or null if it is not exactly the expected shape.</summary>
    public static UnlockChallengeParts? Parse(string challenge)
    {
        var f = challenge.Split('|');
        if (f.Length != 7 || f[0] != Prefix) return null;
        if (f[1] is not ("sudo" or "polkit" or "screen")) return null;
        if (f[5].Length != 64 || !f[5].All(c => c is (>= '0' and <= '9') or (>= 'a' and <= 'f'))) return null;
        if (!long.TryParse(f[6], NumberStyles.None, CultureInfo.InvariantCulture, out var expires)) return null;
        return new UnlockChallengeParts(f[1], f[2], f[3], f[4], f[5], expires);
    }

    /// <summary>Makes a command safe to embed: no <c>|</c>, no control characters, at most 120 characters.</summary>
    public static string CleanCommand(string command)
    {
        var kept = new string(command.Where(c => c != '|' && c >= 0x20).ToArray()).Trim();
        return kept.Length > MaxCommand ? kept[..MaxCommand] : kept;
    }

    /// <summary>True for purposes a PAM/polkit helper may request (not <c>screen</c>).</summary>
    public static bool IsRequestPurpose(string purpose) => purpose is "sudo" or "polkit";

    /// <summary>Checks an ECDSA P-256/SHA-256 DER signature (base64) over the challenge. Never throws.</summary>
    public static bool Verify(string publicKeyB64, string challenge, string signatureB64)
    {
        try
        {
            using var key = ECDsa.Create();
            key.ImportSubjectPublicKeyInfo(Convert.FromBase64String(publicKeyB64), out _);
            return key.VerifyData(Encoding.UTF8.GetBytes(challenge), Convert.FromBase64String(signatureB64),
                HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence);
        }
        catch
        {
            return false;
        }
    }

    /// <summary>Short fingerprint of a public key for eyeball comparison (<c>AB12 CD34 EF56 7890 1A2B</c>), or null for a bad key.</summary>
    public static string? CheckCode(string publicKeyB64)
    {
        try
        {
            var der = Convert.FromBase64String(publicKeyB64);
            using var key = ECDsa.Create();
            key.ImportSubjectPublicKeyInfo(der, out _);
            var hex = Convert.ToHexString(SHA256.HashData(der))[..20];
            return string.Join(' ', Enumerable.Range(0, 5).Select(i => hex.Substring(i * 4, 4)));
        }
        catch
        {
            return null;
        }
    }
}
