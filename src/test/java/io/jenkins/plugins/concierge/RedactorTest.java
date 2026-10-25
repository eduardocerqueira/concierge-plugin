package io.jenkins.plugins.concierge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RedactorTest {

    private final Redactor redactor = new Redactor(List.of("my-exact-api-key-value"));

    private void assertHidden(String input, String secret) {
        String out = redactor.redact(input);
        assertFalse(out.contains(secret), "leaked " + secret + " in: " + out);
        assertTrue(out.contains(Redactor.MASK), out);
    }

    @Test
    void removesWellKnownTokens() {
        assertHidden("key AKIAIOSFODNN7EXAMPLE in use", "AKIAIOSFODNN7EXAMPLE");
        assertHidden("ghp_aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789ab", "ghp_aBcDeFgHiJkLmNoPqRsTuVwXyZ0123456789ab");
        assertHidden("token github_pat_11ABCDEFG0123456789_abcdefghijklmnopqrstuvwxyz", "github_pat_11ABCDEFG");
        assertHidden("glpat-abcdefghij1234567890", "glpat-abcdefghij1234567890");
        assertHidden("xoxb-123456789012-abcdefghijkl", "xoxb-123456789012-abcdefghijkl");
        assertHidden("sk-ant-api03-abcdefghijklmnopqrstuvwxyz012345", "abcdefghijklmnopqrstuvwxyz012345");
        assertHidden("sk-proj-abcdefghijklmnopqrstuvwxyz", "abcdefghijklmnopqrstuvwxyz");
        assertHidden(
                "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnop", "eyJzdWIiOiIxMjM0NTY3ODkwIn0");
    }

    @Test
    void keepsTheKeyNameButHidesTheValue() {
        String out = redactor.redact("DB_PASSWORD=hunter2 and api_key: \"abc def\" client_secret='s3cr3t'");
        assertTrue(out.contains("DB_PASSWORD=" + Redactor.MASK), out);
        assertTrue(out.contains("api_key: " + Redactor.MASK), out);
        assertTrue(out.contains("client_secret=" + Redactor.MASK), out);
        assertFalse(out.contains("hunter2"));
        assertFalse(out.contains("abc def"));
        assertFalse(out.contains("s3cr3t"));
    }

    @Test
    void removesCredentialsInUrlsHeadersAndPemBlocks() {
        assertHidden("cloning https://deploy:pa55word@git.example.com/repo.git", "pa55word");
        assertHidden("Authorization: Bearer abc.def.ghi-123456", "abc.def.ghi-123456");
        assertHidden(
                "-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAAKCAQEA\nabc\n-----END RSA PRIVATE KEY-----",
                "MIIEpAIBAAKCAQEA");
        assertTrue(redactor.redact("https://deploy:pa55word@git.example.com").contains("git.example.com"));
    }

    @Test
    void removesExactLiteralsSuchAsTheApiKeyInUse() {
        assertHidden("echo my-exact-api-key-value", "my-exact-api-key-value");
        // Very short literals would mangle ordinary text, so they are ignored.
        assertEquals("a b c", new Redactor(List.of("a")).redact("a b c"));
    }

    @Test
    void leavesOrdinaryBuildOutputAlone() {
        String log = "[INFO] BUILD SUCCESS\nTests run: 42, Failures: 0\nERROR: connection refused to db:5432";
        assertEquals(log, redactor.redact(log));
    }

    @Test
    void stays_fastOnHostileInput() {
        String hostile = "password=" + "a".repeat(200_000) + " " + "-----BEGIN PRIVATE KEY-----" + "x".repeat(100_000);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> redactor.redact(hostile));
    }
}
