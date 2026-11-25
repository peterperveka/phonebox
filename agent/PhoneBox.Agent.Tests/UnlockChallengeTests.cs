using System.Security.Cryptography;
using System.Text;
using PhoneBox.Agent;
using Xunit;

public sealed class UnlockChallengeTests
{
    private static string Good(string? nonce = null) =>
        $"phonebox-unlock-v1|sudo|peter|fedora|sudo dnf up|{nonce ?? new string('a', 64)}|123";

    private static (string PublicKey, ECDsa Key) NewKey()
    {
        var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        return (Convert.ToBase64String(key.ExportSubjectPublicKeyInfo()), key);
    }

    private static string Sign(ECDsa key, string text) => Convert.ToBase64String(
        key.SignData(Encoding.UTF8.GetBytes(text), HashAlgorithmName.SHA256, DSASignatureFormat.Rfc3279DerSequence));

    [Fact]
    public void CreateThenParseRoundTrips()
    {
        var p = UnlockChallenge.Parse(UnlockChallenge.Create("sudo", "peter", "fedora", "sudo dnf up", 123));
        Assert.NotNull(p);
        Assert.Equal("sudo", p.Purpose);
        Assert.Equal("peter", p.User);
        Assert.Equal("fedora", p.Host);
        Assert.Equal("sudo dnf up", p.Command);
        Assert.Equal(123, p.ExpiresAt);
        Assert.Equal(64, p.Nonce.Length);
    }

    [Fact]
    public void TwoChallengesHaveDifferentNonces()
    {
        var a = UnlockChallenge.Parse(UnlockChallenge.Create("sudo", "u", "h", "c", 1))!;
        var b = UnlockChallenge.Parse(UnlockChallenge.Create("sudo", "u", "h", "c", 1))!;
        Assert.NotEqual(a.Nonce, b.Nonce);
    }

    [Fact]
    public void ParseRejectsWrongShape()
    {
        var n = new string('a', 64);
        Assert.Null(UnlockChallenge.Parse($"phonebox-unlock-v1|sudo|peter|fedora|{n}|123"));
        Assert.Null(UnlockChallenge.Parse($"phonebox-unlock-v2|sudo|peter|fedora|c|{n}|123"));
        Assert.Null(UnlockChallenge.Parse($"phonebox-unlock-v1|ssh|peter|fedora|c|{n}|123"));
        Assert.Null(UnlockChallenge.Parse("phonebox-unlock-v1|sudo|peter|fedora|c|XYZ|123"));
        Assert.Null(UnlockChallenge.Parse($"phonebox-unlock-v1|sudo|peter|fedora|c|{n.ToUpperInvariant()}|123"));
        Assert.Null(UnlockChallenge.Parse($"phonebox-unlock-v1|sudo|peter|fedora|c|{n}|soon"));
    }

    [Theory]
    [InlineData("sudo")]
    [InlineData("polkit")]
    [InlineData("screen")]
    public void ParseAcceptsAllThreePurposes(string purpose)
    {
        Assert.NotNull(UnlockChallenge.Parse(UnlockChallenge.Create(purpose, "u", "h", "-", 1)));
    }

    [Fact]
    public void OnlySudoAndPolkitAreRequestPurposes()
    {
        Assert.True(UnlockChallenge.IsRequestPurpose("sudo"));
        Assert.True(UnlockChallenge.IsRequestPurpose("polkit"));
        Assert.False(UnlockChallenge.IsRequestPurpose("screen"));
    }

    [Fact]
    public void CleanCommandStripsPipesAndControlChars()
    {
        Assert.Equal("abc", UnlockChallenge.CleanCommand("a|b\nc"));
        Assert.Equal(120, UnlockChallenge.CleanCommand(new string('x', 200)).Length);
    }

    [Fact]
    public void ValidSignatureVerifies()
    {
        var (pub, key) = NewKey();
        var c = Good();
        Assert.True(UnlockChallenge.Verify(pub, c, Sign(key, c)));
    }

    [Fact]
    public void SignatureForOtherChallengeFails()
    {
        var (pub, key) = NewKey();
        Assert.False(UnlockChallenge.Verify(pub, Good(new string('b', 64)), Sign(key, Good())));
    }

    [Fact]
    public void GarbageNeverThrows()
    {
        var (pub, _) = NewKey();
        Assert.False(UnlockChallenge.Verify("!!", Good(), "??"));
        Assert.False(UnlockChallenge.Verify(pub, Good(), ""));
    }

    [Fact]
    public void CheckCodeIsTwentyHexInGroupsOfFour()
    {
        var (pub, key) = NewKey();
        var code = UnlockChallenge.CheckCode(pub);
        Assert.NotNull(code);
        Assert.Matches("^[0-9A-F]{4}( [0-9A-F]{4}){4}$", code);
        var hex = Convert.ToHexString(SHA256.HashData(key.ExportSubjectPublicKeyInfo()))[..20];
        Assert.Equal(hex, code.Replace(" ", ""));
        Assert.Null(UnlockChallenge.CheckCode("!!"));
    }
}
