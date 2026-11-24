<div align="center">

<img src="docs/icon.svg" width="128" alt="MRTT">

# MRTT

**Туннель через TURN-серверы звонков VK, MAX и Jitsi**

![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
![Rust](https://img.shields.io/badge/Rust-000000?style=for-the-badge&logo=rust&logoColor=white)

[![Downloads](https://img.shields.io/github/downloads/ttrroyy/mrtt/total?label=downloads-total&color=brightgreen)](https://github.com/ttrroyy/mrtt/releases)

</div>

Android-клиент и сервер для туннеля через звонки. Клиент поднимает VPN на устройстве и передаёт трафик через TURN-серверы звонков VK, MAX или Jitsi, а сервер на VPS принимает его и выпускает в интернет. Для провайдера соединение выглядит как обычный звонок.

## Скачать

Свежий APK - в [Releases](https://github.com/ttrroyy/mrtt/releases/latest). Нужен Android 8.0 или новее; сборки есть для arm64-v8a, armeabi-v7a, x86_64 и универсальная. Сервер для Linux на amd64, arm64 и armv7 лежит внутри APK, в папке `assets`: APK открывается как обычный zip-архив.

## Особенности

**Три вида звонков.** Трафик может идти через TURN-серверы VK, MAX или Jitsi.

**Деплой сервера.** Сервер разворачивается на VPS по SSH прямо из приложения, после установки приложение выдаёт ссылку на подключение. Сервер под все архитектуры уже лежит внутри APK, отдельно ничего скачивать не нужно.

## CSQTT 2.5.0 и 2.1.9

В [Releases](https://github.com/ttrroyy/mrtt/releases) сохранены сборки CSQTT 2.5.0 Legacy и 2.1.9 Legacy: клиент, сервер и деплой работают так же, как в оригинале.

## Лицензия

[PolyForm Noncommercial 1.0.0](LICENSE). Код можно использовать, изменять и распространять только в некоммерческих целях. Продажа, платные сервисы и другое коммерческое использование без письменного разрешения автора CSQTT запрещены.

## Благодарности

- [CSQTT](https://github.com/amurcanov/csqtt): туннель через TURN-серверы звонков VK
- [olcRTC](https://github.com/openlibrecommunity/olcrtc): туннель через сервисы видеовстреч
- [webrtc-rs](https://github.com/webrtc-rs/webrtc): WebRTC на Rust
- [primp](https://github.com/deedy5/primp): HTTP-клиент, который выдаёт себя за браузер
- [netstack-smoltcp](https://github.com/cavivie/netstack-smoltcp): сетевой стек на smoltcp

## Обсуждение и поддержка

[Группа в Telegram](https://t.me/raw_comm)

Понравилось - ставь ⭐ репозиторию: это помогает другим найти проект.
