# StickerBoard

Teclado Android (Kotlin, `InputMethodService`) con:

- Sugerencias de stickers según lo que escribes (todo en el dispositivo).
- 6 temas de color (botón 🎨).
- Traductor sin conexión con ML Kit (botón 🌐): traduce el texto del campo y lo reemplaza.
- Paquetes de stickers importables (.zip / .wastickers / imágenes sueltas).
- Se adapta a la pantalla: altura de teclas según orientación, insets de la barra de navegación y reajuste al girar.

## Compilar

1. Abre la carpeta `StickerBoard` en Android Studio (genera el Gradle wrapper solo).
2. Sync + Run en un teléfono o emulador (minSdk 24).
3. En la app: **Activar teclado** → **Elegir teclado** → importa paquetes.

No pude compilar el APK en este entorno (sin Android SDK ni red), así que el código está sin probar. Espera tener que corregir algún detalle al primer build.

## Formato de paquetes

Carpeta en `filesDir/packs/<paquete>/` con imágenes (`webp/png/jpg/gif`) y un `tags.json` opcional:

```json
{ "gato1.webp": ["😂", "risa", "gato"] }
```

Al importar un zip con `contents.json` (formato de paquetes de WhatsApp) los emojis de cada sticker se usan como etiquetas automáticamente. Sin etiquetas, solo se usan las palabras del nombre del archivo.

## Límites importantes

- **Sticker Maker / Sigstick:** no leo sus apps directamente. Para Sigstick puedes pegar el enlace de un paquete; para otras apps exporta el paquete e impórtalo como zip o imágenes.
- **Compatibilidad con todas las apps:** un teclado solo puede insertar imágenes en apps que declaran soporte de contenido enriquecido (`commitContent`). WhatsApp, Telegram y Gboard-compatibles suelen aceptarlo; otras no, y entonces se muestra un aviso. No existe forma de forzarlo.
- **"Conforme a la conversación":** el teclado solo ve lo que *tú* escribes en el campo, no los mensajes recibidos. Las sugerencias usan tus últimas palabras.
- **Traductor:** los modelos de idioma se descargan la primera vez (necesita internet); después funciona sin conexión.
- **Enlaces de Sigstick:** la descarga por enlace lee la página del paquete y prueba webp, gif y png en el CDN; si no encuentra el archivo completo usa la miniatura (128 px). No pude probarla contra el sitio real. Úsala solo con paquetes que tengas derecho a usar.
- **Animados:** se reproducen en la barra de sugerencias desde Android 9; que la app destino los anime al enviarlos depende de ella.
- Pendiente: editor de etiquetas dentro de la app, emojis, autocapitalización, corrección de texto, ícono propio.

## Subir a GitHub y actualizar sin desinstalar

Android solo actualiza una app encima si cumple tres cosas: mismo `applicationId` (`com.example.stickerboard`), **misma clave de firma** y un `versionCode` mayor. El workflow de `.github/workflows/build.yml` se encarga de las dos últimas.

### 1. Crea tu clave de firma (una sola vez)

```bash
keytool -genkeypair -v -keystore release.jks -alias stickerboard \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks > release.jks.b64   # en macOS: base64 -i release.jks
```

Guarda `release.jks` en un lugar seguro y con respaldo. **Si pierdes esta clave, nadie podrá actualizar la app sin desinstalarla.** No la subas al repositorio (el `.gitignore` ya la excluye).

### 2. Agrega los secretos en GitHub

En el repositorio: Settings → Secrets and variables → Actions → New repository secret.

| Secreto | Valor |
|---|---|
| `KEYSTORE_BASE64` | contenido de `release.jks.b64` |
| `KEYSTORE_PASSWORD` | contraseña del keystore |
| `KEY_ALIAS` | `stickerboard` |
| `KEY_PASSWORD` | contraseña de la clave |

### 3. Sube el proyecto

```bash
git init -b main
git add .
git commit -m "Primer commit"
git remote add origin https://github.com/TU_USUARIO/StickerBoard.git
git push -u origin main
```

Cada push a `main` compila un APK firmado (pestaña Actions → artefacto `StickerBoard-apk`). Para publicar una versión descargable:

```bash
git tag v0.1.0
git push origin v0.1.0
```

El APK queda en la sección Releases. Instálalo encima de la versión anterior: se actualiza conservando los datos y los paquetes de stickers.

**Importante:** si ya instalaste un APK compilado desde Android Studio (firmado con la clave de depuración), la primera vez que instales el de GitHub tendrás que desinstalar esa versión, porque la firma es distinta. A partir de ahí, todas las actualizaciones van encima.
