# 📞 TrackWave Caller ID & Çağrı Takipçisi - Sistem Dokümantasyonu

> **TrackWave CallTracker**, şirket/çağrı merkezi Android telefonlarına kurulan, gelen hücresel aramaları ve **WhatsApp / WhatsApp Business** sesli aramalarını donanımsal seviyede yakalayarak **TrackWave Web Paneli ve Masaüstü (WPF)** ekranlarına anlık pop-up olarak ileten yerel (Native Kotlin) bir arka plan servis uygulamasıdır.

---

## 📑 İçindekiler
1. [Sistem Mimarisi & Çalışma Akışı](#1-sistem-mimarisi--çalışma-akışı)
2. [Teknoloji Yığını & Android İzinleri](#2-teknoloji-yığını--android-izinleri)
3. [Çekirdek Servisler & Kod Yapısı](#3-çekirdek-servisler--kod-yapısı)
4. [SignalR & Web/Masaüstü Entegrasyon Akışı](#4-signalr--webmasaüstü-entegrasyon-akışı)
5. [Kurulum & APK Derleme](#5-kurulum--apk-derleme)

---

## 🏗️ 1. Sistem Mimarisi & Çalışma Akışı

1. **Telefon Çalar:** Müşteri şirketi normal hattan veya WhatsApp üzerinden arar.
2. **Servis Yakalar:** `CallDetectorService` veya `WhatsAppCallListenerService` numarayı saniyenin onda birinde izole eder.
3. **API'ye Gönderilir:** `POST /api/calls/log` ile sunucuya iletilir.
4. **Müşteri Eşleştirilir:** API, veri tabanından müşterinin adını, bölgesini ve **aktif açık sipariş sayısını** çeker.
5. **SignalR Yayını:** SignalR Hub üzerinden tüm açık Web Panellerine ve Masaüstü ekranlarına `ReceiveIncomingCall` event'i fırlatılır.
6. **Pop-Up Açılır:** Web ekranının sağ altında *"Ahmet Yılmaz Arıyor - 2 Aktif Sipariş"* kartı çıkar; tıklandığında doğrudan müşteri detayına gidilir.

---

## ⚙️ 2. Teknoloji Yığını

* **Dil & Platform:** Native Kotlin, Android SDK 26 - 34 (Android 8.0 - 14+)
* **Ağ & API:** Retrofit 2 + OkHttp 3 + Gson Converter
* **Arka Plan Servisleri:** Android Foreground Service (`dataSync`), `NotificationListenerService`, `BootReceiver` (Otomatik başlama).

---

## 📂 3. Çekirdek Servisler

* **`CallDetectorService.kt`:** Telefon durumunu (`RINGING`, `OFFHOOK`, `IDLE`) dinleyen kalıcı ön plan servisi.
* **`WhatsAppCallListenerService.kt`:** WhatsApp ve WhatsApp Business sesli aramalarını bildirim katmanından yakalayan servis.
* **`ApiService.kt`:** Arama loglarını API'ye gönderen Retrofit istemcisi.
* **`MainActivity.kt`:** Sunucu IP adresi, API token ve test arama ekranı.

---

## 🚀 4. Kurulum & APK Derleme

```bash
# Proje dizinine gidin
cd TrackWaveCallTracker

# Release APK derleyin
./gradlew clean
./gradlew assembleRelease

# Üretilen dosya:
# app/build/outputs/apk/release/app-release.apk
```
