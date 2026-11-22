# Zafiro-Latino

Repositorio de extensiones para [CloudStream](https://github.com/recloudstream/cloudstream) en español.

## Extensiones

| Plugin | Sitio | Contenido |
|---|---|---|
| Zafiro | https://cinehdplus.org | Películas y series (Latino, Castellano…) |

## Instalar en CloudStream

1. En CloudStream: **Ajustes → General** y desactiva **Proxy de GitHub** (si está activo, las
   actualizaciones pueden tardar días en llegar porque pasan por la caché de jsDelivr).
2. **Ajustes → Extensiones → Añadir repositorio** y pega:

   ```
   https://raw.githubusercontent.com/raiderking2022-wq/Zafiro-Latino/refs/heads/builds/zafiro.json
   ```
3. Instala **Zafiro**.

## Nuvio

La misma extensión existe como proveedor para Nuvio en `nuvio/`, con sus propios extractores de
VOE, Streamtape y Mixdrop, porque Nuvio no los trae. En Nuvio: **Ajustes → Plugins / Proveedores → Añadir
repositorio**:

```
https://raw.githubusercontent.com/raiderking2022-wq/Zafiro-Latino/refs/heads/main/nuvio/manifest.json
```

Al cambiar `nuvio/providers/zafiro.js`, sube la versión en `nuvio/manifest.json`, tanto en `version` como en
`filename?v=`.

## Cómo funciona Zafiro

Cada servidor de CineHDPlus pasa por una cadena de páginas antes del video:

`player.php` → `goto.php` (formulario) → `rd.php` → `redir_ddh.php` → acortador *safelink*
(`fotise.com`) → servidor real (VOE, Streamtape, Mixdrop, Streamplay).

El plugin recorre esa cadena como un navegador (formularios, redirecciones, cookies) y busca el
enlace final en claro o codificado en base64. Si no lo encuentra, abre `goto.php` en un WebView
oculto como último recurso.

## Publicación

GitHub Actions (`.github/workflows/build.yml`) compila cada push y publica en la rama `builds`:

- `Zafiro-v<N>.cs3`: un archivo por versión, para que la app nunca mezcle un `.cs3` en caché con
  una lista nueva.
- `plugins-<commit>.json`: lista con nombre nuevo en cada build; las listas antiguas se reescriben
  con la versión actual.
- `zafiro.json` / `repo.json`: el repositorio que se añade en CloudStream.
- Al final se purga la caché de jsDelivr (6 min después, cuando GitHub ya sirve lo nuevo).

Al cambiar el plugin, sube `version` en `Zafiro/build.gradle.kts`.

Compilar en local (JDK 17 + Android SDK): `./gradlew Zafiro:make`
