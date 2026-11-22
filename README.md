# Zafiro-Latino

Repositorio de extensiones para [CloudStream](https://github.com/recloudstream/cloudstream) en español.

## Extensiones

| Plugin | Sitio | Contenido |
|---|---|---|
| Zafiro | https://cinehdplus.org | Películas y series |

## Instalar en CloudStream

1. Abre CloudStream → **Ajustes → Extensiones → Añadir repositorio**.
2. Pega esta URL:

   ```
   https://raw.githubusercontent.com/raiderking2022-wq/Zafiro-Latino/refs/heads/builds/repo.json
   ```

   (o directamente la lista de plugins: `https://raw.githubusercontent.com/raiderking2022-wq/Zafiro-Latino/refs/heads/builds/plugins.json`)
3. Instala **Zafiro**.

## Compilar

GitHub Actions (`.github/workflows/build.yml`) compila cada push y publica los `.cs3` y
`plugins.json` en la rama `builds`.

Localmente (requiere JDK 17 y Android SDK):

```
./gradlew Zafiro:make
```

Al cambiar un plugin, sube `version` en su `build.gradle.kts` para que CloudStream ofrezca la actualización.
