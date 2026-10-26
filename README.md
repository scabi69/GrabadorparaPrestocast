# 🎙️ Grabador Prestocast (v1.2.7.1)

**Grabador Prestocast** es una aplicación nativa para Android desarrollada en Kotlin y Jetpack Compose, diseñada para grabar audio en alta fidelidad (formato `.m4a`/`AAC` nativo o archivos locales importados), transcodificar localmente a **MP3 genuino** mediante el motor C/JNI **LAME MP3**, integrarse con el servicio de postprocesamiento en la nube **Auphonic.com** (nivelación inteligente, reducción de ruido con IA y salida MP3), y proporcionar un sistema de guión / teleprompter para podcasts, boletines y locuciones.

---

## ✨ Funcionalidades Principales

### 🔴 Grabación de Audio Continua, Bluetooth y Guardado Configurable
- **Pausa Automática de Reproducción de Medios del Sistema**: Al presionar grabar, la app emite la señal de pausa del sistema (`KEYCODE_MEDIA_PAUSE` y `AUDIOFOCUS_GAIN_TRANSIENT` vía `AudioManager`) para pausar cualquier otra app que esté reproduciendo audio en el móvil (Spotify, podcasts, navegadores, etc.), asegurando que la reproducción **no se reanude** al terminar.
- **⏱️ Avisos de Tiempo Sonoros Exclusivos por Auriculares**: Configuración de avisos sonoros que emiten pitidos de frecuencias ascendentes (800 Hz, 1000 Hz, 1200 Hz y 1500 Hz) a los **-20s, -10s, -5s y -2s** antes de alcanzar el tiempo fijado (1 min, 10 min o personalizado). Se reproducen **exclusivamente a través de auriculares** (Bluetooth o cable) para no ser captados por el micrófono ni sonar por el altavoz.
- **💬 Globos Flotantes de Aviso de Tiempo (Superposición sin Desplazamiento)**: En los mismos momentos de los avisos (-20s, -10s, -5s, -2s), la app despliega un globo flotante redondeado en la parte superior central de la pantalla (`Alignment.TopCenter`), **flotando sobre la aplicación sin desplazar ni un píxel del guión o controles**.
- **Teleprompter Expandible a Pantalla Completa**: Durante la grabación, el área del guión/teleprompter se expande hacia abajo hasta el borde inferior de la pantalla (`weight(1f)`), permitiendo una lectura cómoda de guiones extensos con desplazamiento vertical.
- **Ocultación Dinámica de Ajustes**: Durante la grabación se ocultan suavemente la tarjeta de ajustes superiores, el Checkbox de guardado automático y el botón de abrir archivo local para ofrecer una interfaz de grabación limpia y libre de distracciones.
- **Grabación en Formato Nativo `.m4a` / `AAC`**: Las grabaciones originales se crean con extensión `.m4a` ofreciendo máxima fidelidad y rendimiento sin latencia ni distorsiones.
- **Soporte Bluetooth SCO y Gestión de Micrófonos**: Ruteo nativo de voz con perfiles **Bluetooth SCO** (`startBluetoothSco` y `setCommunicationDevice` para Android 12+) para grabar audio a través de auriculares y micrófonos Bluetooth sin silencios ni caídas de señal.
- **Selección de Fuente de Entrada de Audio**: Desplegable en el menú de opciones para elegir el micrófono activo (integrado del teléfono, auriculares con cable, Bluetooth SCO o USB) con botón de refresco en vivo 🔄 e indicador visual en la tarjeta superior.
- **Guardado Automático Configurable (Checkbox)**: Opción en la tarjeta de ajustes superior para activar o desactivar el guardado automático de la grabación original en la carpeta **Descargas** (`Environment.DIRECTORY_DOWNLOADS` / `MediaStore`) al detener la grabación. Su estado se guarda de forma persistente.
- **Respeto de Extensión de Archivos Importados**: Al abrir o recibir archivos mediante el menú *"Compartir"* desde otras aplicaciones (WhatsApp, Telegram, etc.), se conserva intacta la extensión del archivo original (ej. `.mp3` o `.m4a`).
- **Grabación Opcional sin Guión**: Diálogo de confirmación al presionar el botón de grabar cuando el guión está vacío (*"Continuar sin Guión"* o *"Escribir Guión"*).
- **Grabación Continua con Pantalla Apagada**: Integración con **Foreground Service** (`AudioRecordingService.kt`) y `PARTIAL_WAKE_LOCK` para evitar interrupciones si la pantalla del móvil se apaga durante la sesión.
- **Parámetros y Nomenclatura Personalizables**: Bitrate (64, 96, 128, 160, 192, 256, 320 kbps), Canales (Stereo/Mono), Hercios (22.05, 32, 44.1, 48 kHz), prefijo de grabación (`Grabacion_`) y sufijo para Auphonic (`_procesado`).
- **Resaltado Visual en Desplegables**: En todos los desplegables de configuración, la opción elegida actualmente aparece resaltada con **texto en negrita**, **icono de verificación ✔️** y **fondo coloreado**.

### 📜 Editor de Guión / Escaleta e Integración Avanzada de Portapapeles
- **Persistencia en Rotación de Pantalla 🔄**: El contenido del guión, posición del cursor y selecciones se conservan intactos al rotar la pantalla del móvil mediante `rememberSaveable(stateSaver = TextFieldValue.Saver)`.
- **Diálogo de Confirmación de Cambios sin Guardar ⚠️**: Al intentar salir del editor sin haber guardado los cambios realizados, la app muestra un cuadro de diálogo interactivo con las opciones *"Guardar y Salir"*, *"Descartar"* o *"Cancelar"*.
- **Inserción en la Posición Exacta del Cursor 📋**: El botón **"Pegar"** utiliza `TextFieldValue` para insertar el texto del portapapeles directamente en la posición donde se encuentra el cursor o selección activa, **sin añadir saltos de línea ni espacios adicionales**.
- **Botones "Pegar" y "Borrar" Siempre Visibles**: Ambos botones se mantienen permanentemente visibles lado a lado en la barra de instrucciones, sin importar si el cuadro de texto está vacío o lleno.
- **Título "Guión / Escaleta"**: Título limpio y compacto en la TopBar.
- **Botón Inferior "Guardar y Grabar"**: Ajustado con tamaño de fuente compacto para que encaje perfectamente en una sola línea.
- **Persistencia de Texto**: El guión introducido se guarda de forma persistente en `SharedPreferences` para no perderlo al cerrar o reiniciar la aplicación.

### 🎨 Pantalla Splash Screen Vistosa y Diálogo "Acerca de"
- **Apertura Inicial (Splash Screen)**: Pantalla de presentación colorida a toda pantalla (dentro de los límites de la app, respetando la barra de notificaciones) con un fondo degradado vibrante, icono central heroico de audio, nombre de la app, versión actual (`v1.2.7.1`) y timestamp de compilación en formato `YYYYMMDDHHMM`. Se cierra automáticamente tras 3.0 segundos.
- **Sección "Acerca de" en Opciones**: Botón **"Acerca de"** ℹ️ añadido al final de la ventana de *Opciones de Grabación* que abre este cuadro informativo con la versión y fecha/hora exacta de compilación.

### ⚡ Conversión Local a MP3 (`Mp3Transcoder.kt` + `libandroidlame.so`)
- **Compatibilidad 16 KB Page Size Alignment**: Configuración de empaquetado nativo `useLegacyPackaging = false` para eliminar advertencias de alineación de páginas de 16 KB en Android 15/16 (API 35+).
- **Botón Dedicado "Convertir a MP3"**: Ubicado en la tarjeta del reproductor entre los botones *"Guardar"* y *"Enviar a Auphonic.com"*.
- **Transcodificación On-Device**: Convierte localmente archivos `.m4a` a **MP3 nativo real** (`.mp3`) usando el motor C nativo de **LAME MP3** (`libandroidlame.so`).
- **Mantenimiento 1:1 de Parámetros**: Extrae la tasa de bits (kbps), la frecuencia de muestreo (Hz) y los canales (Stereo/Mono) del archivo de origen y los aplica exactamente al archivo MP3 generado.
- **Guardado Directo en Descargas**: Escribe la pista `.mp3` con el mismo nombre base directamente en la carpeta **Descargas** (ej. `Grabacion_20250228_153000.mp3`), de forma limpia sin modificar la sección de Auphonic.

### 🎛️ Integración Avanzada con Auphonic.com (Postprocesamiento Cloud)
- **Procesamiento de Voz con Inteligencia Artificial**: Envío directo a la API v2 de Auphonic para nivelación automática de volumen (Loudness Normalization / -16 LUFS), reducción de ruido de fondo y codificación a MP3.
- **Sincronización de Presets**: Presets del sistema (`auphonic_cleaner`, `default`, `speech_isolation`), sincronización automática de presets de la cuenta del usuario desde Auphonic.com y entrada manual personalizada.
- **Ventana Dedicada de Recorte y Escucha (`AuphonicResultScreen`)**:
  - **Reproductor Integrado**: Permite escuchar el audio recibido de Auphonic antes de aplicar el recorte.
  - **Métricas de Duración**: Visualiza la duración original, la duración recibida de Auphonic y el tiempo excedente a recortar.
  - **Opciones de Recorte Excluyentes**:
    - `⚫ Cortar al final`: Conserva el inicio y desecha la cola sobrante.
    - `⚪ Cortar al inicio`: Conserva el final y desecha la cabecera sobrante.
    - `⚪ Sin recortar`: Mantiene todo el audio recibido.
  - Al confirmar, aplica el recorte, elimina etiquetas ID3/APE sobrantes y guarda el resultado MP3 en **Descargas** (ej. `Grabacion_20250228_153000_procesado.mp3`).

---

## 📜 Historial de Cambios (Changelog)

### 🟢 v1.2.7.1
- **Persistencia al Rotar Pantalla**: Conservación del texto, selección y cursor del editor de guión al cambiar la orientación del dispositivo mediante `rememberSaveable`.
- **Solución Compatibilidad 16 KB Page Size**: Configurado `useLegacyPackaging = false` en `packaging { jniLibs }` eliminando la advertencia de alineación de librerías JNI en Android 15/16.
- **Diseño Ultra-Compacto Responsivo de 1 Línea**: Reducidos tamaños de fuente y truncamiento `Ellipsis` para garantizar que encajen en 1 sola línea:
  - Ajustes de grabación.
  - Fuente de entrada seleccionada.
  - Opción de guardado automático en Descargas.
  - Fichero de audio seleccionado y su nombre.
  - Pestañas de audio (*Original* vs *Procesado MP3*).
  - Botón *Compartir*.
- **Subida de Versión**: Actualizado a `v1.2.7.1` (`versionCode = 11`).

### 🟢 v1.2.7
- **Licencia MIT con Atribución**: Añadido archivo de licencia `LICENSE` reconociendo a **Xabi Otero** como desarrollador principal y a **Gemini** como asistente de IA.
- **Rediseño Vistoso de Splash Screen**: Fondo degradado a toda pantalla, icono de audio destacado, versión `v1.2.7` y timestamp `YYYYMMDDHHMM`.
- **Formato Limpio de Fecha/Hora**: Eliminado el prefijo `"Compilado: "`.

### 🟢 v1.2.6
- **Pantalla Splash Screen & "Acerca de"**: Presentación inicial e información en opciones.
- **Globos Flotantes de Aviso sin Desplazamiento**: Globos redondeados en `Alignment.TopCenter` por encima de la aplicación.
- **Diálogo de Confirmación en Editor**: Confirmación al salir sin guardar.
- **Avisos Sonoros de Tiempo por Auriculares**: Pitidos en auriculares (-20s, -10s, -5s, -2s).

---

## 🛠️ Requisitos del Proyecto

- **Android SDK**: Mínimo API 24 (Android 7.0) / Target API 35 (Android 15).
- **Versión de la App**: v1.2.7.1 (`versionCode 11`).
- **Lenguaje**: Kotlin 2.1.0.
- **UI Framework**: Jetpack Compose (Material 3).
- **Herramienta de Construcción**: Gradle 9.6 / AGP 8.7.3 (con Java 17/21).

---

## 📄 Licencia

Este proyecto está licenciado bajo la **Licencia MIT**.

- **Desarrollador Principal**: **Xabi Otero**
- **Asistencia de Desarrollo**: Desarrollado con la colaboración de **Gemini** como asistente de Inteligencia Artificial.

Consulta el archivo **[`LICENSE`](file:///G:/A%20mi%C3%B1a%20unidade/Documentos/Programacion/Android%20Studio/GrabadorparaPrestocast/LICENSE)** para más detalles.
