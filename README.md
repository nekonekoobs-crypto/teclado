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

## Novedades

- **Mis paquetes:** botón "Ver mis paquetes de stickers" en la app principal. Muestra cada paquete con todos sus stickers (los animados se reproducen) y permite borrarlo.
- **Texto predictivo:** barra de 3 palabras sobre el teclado. Sugiere completar la palabra que escribes o la siguiente palabra. Trae un vocabulario básico en español e inglés y aprende lo que escribes (una palabra cuenta cuando la has escrito al menos 2 veces). Lo aprendido se guarda solo en el teléfono y se borra con "Borrar palabras aprendidas".
- **Corrección ortográfica:** usa el corrector del sistema Android (el que tengas activo en Ajustes → Idioma y entrada). Las sugerencias aparecen resaltadas en la barra. Al pulsar espacio o un signo se autocorrige solo si el corrector está seguro; si borras justo después, vuelve tu palabra original y no se vuelve a corregir. Si el teléfono no tiene corrector activo, la barra ofrece correcciones simples (una letra de diferencia) pero no autocorrige.
- **Privacidad:** no se predice, corrige ni aprende en contraseñas, correos y direcciones web, ni en campos que piden "sin aprendizaje personalizado". Ambas funciones se pueden apagar en la app.
- **Descarga por enlace:** ahora reconoce nombres como `0-1.thumb128.png` y `0.thumb128.webp`, no depende del tipo de contenido que declare el servidor y envía `Referer`. Si falla, la app muestra un cuadro con el detalle del error.

## Nombres de stickers

- En **Ver mis paquetes**, cada sticker muestra su nombre. Tócalo para ver la imagen grande y editar el nombre (estado de ánimo o acción, separado por comas: `feliz, abrazo`).
- Los nombres se guardan en `labels.json` dentro de cada paquete. El teclado los usa para sugerir el sticker: coincide con lo que escribes aunque falten acentos y reconoce palabras equivalentes (por ejemplo, un sticker llamado "feliz" también aparece al escribir "contento").
- **Nombrado automático:** al agregar stickers con "+ Agregar", y con el botón "Nombrar automáticamente" para los que no tienen nombre, la app analiza la imagen en el teléfono (ML Kit) y propone un nombre en español. Reconoce objetos y expresiones generales; con dibujos o personajes animados suele dar nombres poco precisos, así que conviene revisarlos. La traducción al español necesita internet la primera vez.
