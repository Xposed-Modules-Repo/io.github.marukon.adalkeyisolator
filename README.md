# OfficeAdalKeyIsolator

专为微软 Office Android 系列应用（`sharedUserId="com.microsoft.office"`）设计的 **LibXposed API 102** 模块：在不修改原版 APK 签名与不破坏 Google Play 自动更新的前提下，为每个同 UID 组件隔离各自的 `AndroidKeyStore` `"AdalKey"` 主密钥别名，解决 `Word`、`Excel`、`PowerPoint`、`OneNote`、`Microsoft 365 (Copilot)` 之间互相覆盖凭证密钥导致的“偶尔首开掉登录 / 需要修复账户 / `Failed to decrypt with key:AdalKey`”问题。

- **仓库地址**: https://github.com/Marukon/OfficeAdalKeyIsolator
- **应用名称**: `OfficeAdalKeyIsolator`（包名 `io.github.marukon.adalkeyisolator`）
- **最低系统要求**: Android 13+（API 33+），LSPosed 1.9.3+（LibXposed API 102）

---

## 问题背景

微软 Office Android 多件套在 `AndroidManifest.xml` 中历史沿用了相同的 `android:sharedUserId="com.microsoft.office"`（共享同一个 Linux UID 与同一个 `AndroidKeyStore` 密钥命名空间），同时却各自在私有数据目录下维护独立的 `shared_prefs/com.microsoft.identity.client.account_credential_cache.xml` 与 `app_<packageName>/adalks` 文件。

由于微软 ADAL / MSAL / OneAuth SDK（`com.microsoft.aad.adal.StorageHelper` 与 `com.microsoft.identity.common.crypto.AndroidAuthSdkStorageEncryptionManager`）将 KeyStore 主密钥别名固定硬编码为 **`"AdalKey"`**，一旦其中任意一个应用触发密钥生成或重建（`keyStore.deleteEntry("AdalKey")`），就会直接覆盖同一个 UID 下正在被其他 Office 应用使用的 `"AdalKey"`，导致其他组件冷启动时本地加密凭证解密失败（日志表现为 `Failed to decrypt with key:AdalKey thumbprint : ...` -> `Loaded 0 Credentials` / `Found [0] Accounts...`）。

---

## 实现原理与自检能力

### 1. 双层幂等隔离 Hook（[MainHook.kt](app/src/main/java/io/github/marukon/adalkeyisolator/MainHook.kt)）
仅当目标密钥别名为精确字符串 `"AdalKey"` 时，将其按当前进程包名重写为 **`"AdalKey_<packageName>"`**（例如 `AdalKey_com.microsoft.office.word`）：

- **MSAL / OneAuth Provider 入口层**：
  - `KeyStoreBackedSecretKeyProviderFactory.create(String, String, Context)`
  - `KeyStoreBackedSecretKeyProvider` 构造函数
  - `AndroidWrappedKeyProvider` 构造函数
- **AndroidKeyStore 系统接口层（覆盖内联常量的 `StorageHelper` 及各类调用方）**：
  - `java.security.KeyStore` 涉及别名的 12 类方法（仅限 `KeyStore.type` 为 `"AndroidKeyStore"` 时改写：`containsAlias`、`deleteEntry`、`getEntry`、`setEntry`、`getKey`、`setKeyEntry`、`getCertificate`、`getCertificateChain`、`setCertificateEntry`、`isKeyEntry`、`isCertificateEntry`、`getCreationDate`）
  - `android.security.keystore.KeyGenParameterSpec$Builder` 构造函数
  - `android.security.KeyPairGeneratorSpec$Builder.setAlias`

### 2. 三重可用性及升级兼容自检（[MainActivity.kt](app/src/main/java/io/github/marukon/adalkeyisolator/MainActivity.kt) & [StatusProvider.kt](app/src/main/java/io/github/marukon/adalkeyisolator/StatusProvider.kt)）
为避免后续微软更新 Office 后改名或重构内部类而难以察觉，模块界面内置三重校验（无需 Root）：

1. **LSPosed 作用域与运行状态**：通过 `XposedService.getScope()` 与 `XposedService.getRunningTargets()` 实时显示各 Office 组件是否已勾选作用域以及当前运行中进程的注入状态（支持一键发起 `requestScope` 申请）。
2. **安装包静态兼容检查**：利用目标应用的 `ClassLoader` 实时扫描 `KeyStoreBackedSecretKeyProviderFactory.create`、`KeyStoreBackedSecretKeyProvider`、`AndroidWrappedKeyProvider`、`StorageHelper` 是否仍匹配当前已安装版本（版本升级后若有类或签名变动会直接提示缺失项）。
3. **进程内真实 Hook 回执**：已注入的目标 Office 进程在初始化与实际触发别名重写时，通过受调用方 UID 校验的 `StatusProvider` 回传 `KeyStore` Hook 数量、MSAL Hook 数量、累计重写次数及当前生效版本号，支持一键向运行中进程发送探测广播实时刷新回执。

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

1. 构建或下载 Release APK 并安装（模块默认不显示桌面图标，仅通过 LSPosed 模块界面打开）。
2. 在 **LSPosed 管理器**中启用 `OfficeAdalKeyIsolator`，确认推荐作用域中的 Office 应用均已勾选（无需重启手机）。
3. **强行停止**已开启作用域的 Office 应用各一次。
4. 重新打开各 Office 应用后，从 LSPosed 打开 `OfficeAdalKeyIsolator` 模块界面，即可确认 `作用域`、`代码兼容检查 (4/4)` 与 `进程内 Hook 回执` 全部正常。

---

## 本地构建

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease --no-daemon
```

输出文件路径：`app/build/outputs/apk/release/app-release.apk`。仓库内已配置 GitHub Actions 工作流（`.github/workflows/build.yml`），可通过 Secrets（`SIGNING_KEYSTORE_BASE64`、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`）注入签名密钥，仓库本身不包含任何 `.jks` / `.keystore` 文件。
