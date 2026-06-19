# Test RSA Keys

This directory holds RSA private keys for **local test only** — they are git-ignored.

## Generating a local dev key

The keys must be regenerated locally after cloning because they are no longer
committed to version control (security best practice).

```bash
# Generate a 2048-bit RSA private key (PEM format, PKCS#8)
openssl genrsa -out dev-rsa-private.pem 2048

# Extract the public key (optional — Spring reads the public key from the
# private key when only the private key is configured)
openssl rsa -in dev-rsa-private.pem -pubout -out dev-rsa-public.pem
```

## Reference

- [GitHub: Removing sensitive data from a repository](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository)
- [OWASP Cryptographic Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cryptographic_Storage_Cheat_Sheet.html)

> **WARNING:** If a private key was previously committed to this repository,
> rotate it immediately. Removing the file from a future commit does not
> invalidate the leaked key — anyone with the historical commit still has it.
