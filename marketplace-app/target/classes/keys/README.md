# Development Authorization Server Keystore

Binary keystores are intentionally not committed to the repository.

Generate a local development keystore with:

```bash
keytool -genkeypair \
  -alias marketplace-auth \
  -keyalg RSA \
  -keysize 2048 \
  -storetype JKS \
  -keystore ./marketplace-app/local/dev-authserver.jks \
  -storepass changeit \
  -keypass changeit \
  -dname "CN=marketplace-dev, OU=Dev, O=Marketplace, L=NA, ST=NA, C=US" \
  -validity 3650
```

Then start the app with:

```bash
export JWT_KEYSTORE_PATH=./marketplace-app/local/dev-authserver.jks
export JWT_KEYSTORE_PASSWORD=changeit
export JWT_KEY_ALIAS=marketplace-auth
export JWT_KEY_PASSWORD=changeit
```
