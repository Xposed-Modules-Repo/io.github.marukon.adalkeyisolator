# AdalKeyIsolator

面向微软 Office Android 系列应用（`sharedUserId="com.microsoft.office"`）的 **LibXposed API 102** 模块：在不修改原版 APK 签名与不触发 Google Play 校验失败的前提下，为每个同 UID 组件隔离各自的 `AndroidKeyStore` `"AdalKey"` 主密钥别名，解决 `Word`、`Excel`、`PowerPoint`、`OneNote`、`Microsoft 365 (Copilot)` 之间互相覆盖凭证密钥导致的“偶尔首开掉登录 / 需要修复账户 / `Failed to decrypt with key:AdalKey`”问题。

- **仓库地址**: https://github.com/Marukon/AdalKeyIsolator
- **包名**: `io.github.marukon.adalkeyisolator`
- **最低系统要求**: Android 13+（API 33+），LSPosed 1.9.3+（LibXposed API 102）

---

## 问题背景

微软 Office Android 多件套在 `AndroidManifest.xml` 中历史沿用了相同的 `android:sharedUserId="com.microsoft.office"`（共享同一个 Linux UID 与同一个 `AndroidKeyStore` 密钥命名空间），同时却各自在私有数据目录下维护独立的 `shared_prefs/com.microsoft.identity.client.account_credential_cache.xml` 与 `app_<packageName>/adalks` 文件。

由于微软 ADAL / MSAL / OneAuth SDK（`com.microsoft.aad.adal.StorageHelper` 与 `com.microsoft.identity.common.crypto.AndroidAuthSdkStorageEncryptionManager`）将 KeyStore 主密钥别名固定硬编码为 **`"AdalKey"`**，一旦其中任意一个应用触发密钥生成或重建（`keyStore.deleteEntry("AdalKey")`），就会直接覆盖同一个 UID 下正在被其他 Office 应用使用的 `"AdalKey"`，导致其他组件冷启动时本地加密凭证解密失败（日志表现为 `Failed to decrypt with key:AdalKey thumbprint : ...` -> `Loaded 0 Credentials` / `Found [0] Accounts...`）。

---

## 实现原理

[MainHook.kt](app/src/main/java/io/github/marukon/adalkeyisolator/MainHook.kt) 采用双层幂等拦截策略，仅当目标密钥别名为精确字符串 `"AdalKey"` 时，将其按当前进程包名重写为 **`"AdalKey_<packageName>"`**（例如 `AdalKey_com.microsoft.office.word`）：

1. **MSAL / OneAuth Provider 入口层**：
   - `KeyStoreBackedSecretKeyProviderFactory.create(String, String, Context)`
   - `KeyStoreBackedSecretKeyProvider` 构造函数
   - `AndroidWrappedKeyProvider` 构造函数
2. **AndroidKeyStore 系统接口层（覆盖内联常量的 `StorageHelper` 及各类调用方）**：
   - `java.security.KeyStore` 涉及别名的 12 类方法（仅限 `KeyStore.type` 为 `"AndroidKeyStore"` 时改写：`containsAlias`、`deleteEntry`、`getEntry`、`setEntry`、`getKey`、`setKeyEntry`、`getCertificate`、`getCertificateChain`、`setCertificateEntry`、`isKeyEntry`、`isCertificateEntry`、`getCreationDate`）
   - `android.security.keystore.KeyGenParameterSpec$Builder` 构造函数
   - `android.security.KeyPairGeneratorSpec$Builder.setAlias`

### 优势

- 完整保留原版 APK 官方签名与 `TokenSharingService` 签名校验互通能力。
- 支持 Google Play 正常自动更新，无需重打包、无需卸载重装或迁移数据目录。

---

## 默认推荐作用域

模块内置 [`scope.list`](app/src/main/resources/META-INF/xposed/scope.list) 覆盖以下 5 个同 UID 微软 Office 组件：

| 应用 | 包名 | 隔离后 KeyStore 别名 |
| --- | --- | --- |
| Microsoft Excel | `com.microsoft.office.excel` | `AdalKey_com.microsoft.office.excel` |
| Microsoft Word | `com.microsoft.office.word` | `AdalKey_com.microsoft.office.word` |
| Microsoft PowerPoint | `com.microsoft.office.powerpoint` | `AdalKey_com.microsoft.office.powerpoint` |
| Microsoft OneNote | `com.microsoft.office.onenote` | `AdalKey_com.microsoft.office.onenote` |
| Microsoft 365 (Copilot) | `com.microsoft.office.officehubrow` | `AdalKey_com.microsoft.office.officehubrow` |

---

## 使用步骤

1. 构建或下载 Release APK 并安装。
2. 在 **LSPosed 管理器**中启用 `AdalKeyIsolator`，确认推荐作用域中的 Office 应用均已勾选（无需重启手机）。
3. **强行停止**已开启作用域的 Office 应用各一次。
4. 首次重新打开各应用时，会因为切换到全新的隔离别名而自动重新生成各自独立的 `AdalKey_<包名>` 与 `adalks` 文件，并从本地个人账户或 `TokenSharingService`（如 OneDrive / Edge / Outlook）完成一次初始化同步；此后各组件之间密钥完全独立，互不覆盖。

---

## 本地构建

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease --no-daemon
```

输出文件路径：`app/build/outputs/apk/release/app-release.apk`。仓库内已配置 GitHub Actions 工作流（`.github/workflows/build.yml`），可通过 Secrets（`SIGNING_KEYSTORE_BASE64`、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`）注入签名密钥，仓库本身不包含任何 `.jks` / `.keystore` 文件。
