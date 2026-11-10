param([string]$BaseUrl = 'http://localhost:14201')

# 05A-3 supersedes the plaintext-secret 05A-2 channel protocol. The security
# verification retains all three channel checks and adds signature/MFA checks.
& "$PSScriptRoot/verify-payment-security.ps1" -BaseUrl $BaseUrl
