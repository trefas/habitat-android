# Habitat Android

Клиент Habitat для Android. Реализует те же сценарии, что и десктопный клиент
`habitat-electron-client`, за исключением регистрации нового пользователя —
аккаунты создаются только из десктопной версии.

## Возможности

- проверка доступности сервера через `GET /healthz`;
- вход по email и паролю (`POST /api/v1/auth/login`);
- сохранение refresh-токена и восстановление сессии после перезапуска;
- refresh-токен хранится в приватном хранилище приложения, резервное копирование данных приложения отключено;
- автоматическое обновление access-токена при `401` с повторным выполнением запроса;
- единовременное обновление токена при параллельных запросах (single-flight);
- принудительный выход, если сервер отозвал refresh-токен или обнаружил его повторное использование;
- защищённый запрос профиля `GET /api/v1/me`;
- смена пароля `PATCH /api/v1/me/password` (все сессии отзываются, требуется повторный вход);
- выход с отзывом refresh-токена на сервере (`POST /api/v1/auth/logout`);
- настройка сервера: протокол (HTTP/HTTPS), IP-адрес или домен, порт;
- светлая и тёмная темы Catppuccin, локализация на английский и русский языки.

## Требования

- JDK 21;
- Android SDK: `platforms;android-37.0`, `build-tools;36.0.0`, `platform-tools`;
- Gradle запускается через входящий в репозиторий wrapper (`./gradlew`).

`local.properties` с путём к SDK в репозиторий не коммитится. Android Studio
создаст его автоматически, а для сборки из терминала достаточно переменной
`ANDROID_HOME`:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
```

## Сборка

```bash
# debug APK
./gradlew assembleDebug

# release APK
./gradlew assembleRelease -PversionName=1.0.0 -PversionCode=1
```

Готовые файлы:

- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`

### Подпись release-сборки

Если переменные окружения не заданы, release APK подписывается отладочным ключом
(подходит для ручной установки, но не для публикации в магазине). Для подписи
собственным ключом задайте:

| Переменная | Описание |
| --- | --- |
| `HABITAT_KEYSTORE_FILE` | путь к файлу keystore |
| `HABITAT_KEYSTORE_PASSWORD` | пароль хранилища |
| `HABITAT_KEY_ALIAS` | alias ключа |
| `HABITAT_KEY_PASSWORD` | пароль ключа |

## CI/CD (GitHub Actions)

- **CI** (`.github/workflows/ci.yml`) запускается на push в `main`/`develop`, на
  pull request в `main` и вручную. Собирает debug и release APK, запускает lint
  и прикладывает APK как артефакты сборки. Если квота хранилища GitHub Actions
  исчерпана, шаг загрузки артефактов не считается ошибкой сборки.
- **CD** (`.github/workflows/cd.yml`) запускается по тегу `v*`. Собирает
  release APK, считает SHA-256 и публикует файлы в GitHub Release.

Релиз:

```bash
git tag v1.0.0
git push origin v1.0.0
```

`versionName` берётся из тега (без префикса `v`), `versionCode` — из номера
запуска workflow.

### Секреты репозитория для подписи

Добавьте в **Settings → Secrets and variables → Actions**:

| Секрет | Описание |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | keystore в base64 (`base64 -w0 release.keystore`) |
| `ANDROID_KEYSTORE_PASSWORD` | пароль хранилища |
| `ANDROID_KEY_ALIAS` | alias ключа |
| `ANDROID_KEY_PASSWORD` | пароль ключа |

Если секреты не заданы, CD всё равно соберёт и опубликует APK, подписанный
отладочным ключом; в логе и summary будет предупреждение.

## Используемые эндпоинты Habitat API

| Метод | Путь | Назначение |
| --- | --- | --- |
| `GET` | `/healthz` | проверка доступности сервера |
| `POST` | `/api/v1/auth/login` | вход |
| `POST` | `/api/v1/auth/refresh` | обновление пары токенов |
| `POST` | `/api/v1/auth/logout` | отзыв refresh-токена |
| `GET` | `/api/v1/me` | профиль текущего пользователя |
| `PATCH` | `/api/v1/me/password` | смена пароля |

Регистрация (`POST /api/v1/auth/register`) намеренно не используется.

## Структура

```
app/src/main/java/com/habitat/android/
├── ApiClient.kt          # HTTP-клиент, токены, refresh, обработка ошибок
├── ConnectionConfig.kt   # настройки сервера и refresh-токен
├── SessionManager.kt     # события принудительного выхода
├── SettingsDialog.kt     # диалог настроек сервера
├── MainActivity.kt       # проверка сервера и маршрутизация
├── LoginActivity.kt      # вход по email/паролю
├── WelcomeActivity.kt    # профиль, смена пароля, выход
└── ErrorMapper.kt        # локализованные сообщения об ошибках
```
