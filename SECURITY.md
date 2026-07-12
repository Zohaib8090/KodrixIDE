# Security Policy

## Credential Management

### Environment Variables (Required)
The following environment variables must be set for secure operation:

```bash
# Firebase Configuration
export FIREBASE_API_KEY="your-firebase-api-key"
export FIREBASE_PROJECT_ID="kodrixide"

# GitHub OAuth
export GITHUB_OAUTH_CLIENT_ID="your-client-id"
export GITHUB_OAUTH_CLIENT_SECRET="your-client-secret"

# Git Credentials
export KODRIX_GH_USER="your-github-username"
export KODRIX_GH_TOKEN="your-github-personal-access-token"
```

### Never Commit
- API keys, tokens, or secrets
- Private keys or certificates
- Credentials in any form
- `.env` or `.env.local` files

## Secure Practices

### For Developers
1. Use `.env.local` (git-ignored) for local development
2. Use secure credential managers (1Password, LastPass, etc.)
3. Rotate tokens regularly
4. Use read-only tokens when possible
5. Never paste credentials in logs or error messages

### For CI/CD
1. Use GitHub Secrets for sensitive data
2. Use organization-level secrets for shared credentials
3. Rotate secrets quarterly
4. Audit secret access logs

## Reporting Security Issues

Please do NOT open public issues for security vulnerabilities. Instead:

1. Email: security@kodrix.local (if available)
2. Use GitHub's private vulnerability reporting
3. Include: Description, severity, reproduction steps, impact

## Credential Rotation

### Firebase
- Rotate API keys every 90 days
- Use separate keys for development and production
- Monitor usage in Firebase Console

### GitHub OAuth
- Revoke compromised tokens immediately
- Use GitHub token expiration settings
- Maintain audit logs of token usage

### Git Access Tokens
- Use fine-grained personal access tokens
- Limit scopes to necessary permissions only
- Set expiration dates (max 1 year)
- Rotate on each major version release

## Vulnerability Scanning

This repository uses automated scanning:
- Dependency scanning (npm, gradle, etc.)
- Secret detection (pre-commit hooks)
- SAST (Static Application Security Testing)

## References

- [OWASP: Secrets Management](https://owasp.org/www-community/Sensitive_Data_Exposure)
- [GitHub: Managing secrets](https://docs.github.com/en/actions/security-guides/encrypted-secrets)
- [12-Factor App: Store config in the environment](https://12factor.net/config)
