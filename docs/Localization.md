# Localization

KSL ships with two built-in server message locales:

- `ru` — Russian (the default)
- `en` — English

The locale system also supports custom YAML files. Message files are stored in the plugin data directory:

```text
plugins/
└── KotlinScriptLoader/
    ├── config.yml
    └── locale/
        ├── en.yml
        ├── ru.yml
        └── template.yml
```

## Choose a built-in language

Set the top-level `language` value in `config.yml`:

```yaml
language: ru
```

Use `en` for English. The change is also available at runtime:

```text
/ksl language
/ksl language en
/ksl language ru
```

Running `/ksl language` without an argument shows the active language and the installed locale IDs. Changing the language saves the selection to `config.yml` and reloads messages immediately; a server restart is not required. The command requires `ksl.admin`.

## Add a custom language

1. Copy `plugins/KotlinScriptLoader/locale/template.yml`.
2. Name the copy with your locale ID, for example `de.yml` or `pt-br.yml`.
3. Translate the YAML values while keeping every key and placeholder intact.
4. Run `/ksl language de` (or the locale ID you created).

Locale filenames must use lowercase letters and digits, with hyphens for subtags. Examples: `de.yml`, `pt-br.yml`, `zh-hans.yml`. The filename without `.yml` is the locale ID.

## Translation rules

The template contains the complete message hierarchy, including command output, runtime diagnostics, and all 273 error-code titles and hints.

- Do not rename YAML keys or change their nesting.
- Keep named placeholders exactly as written, such as `{script}`, `{count}`, or `{version}`.
- Keep command names, permission nodes, API identifiers, file paths, and error codes unchanged.
- Preserve any formatting codes such as `§a` and `§c`, unless you intentionally want to change the message formatting.
- Save locale files as UTF-8.

For example, translate the values in this section but keep the keys:

```yaml
language:
  current: "Current KSL language: {language}"
  changed: "KSL language changed to {language}."
  invalid: "Language '{language}' is unavailable. Available: {available}"
```

## Fallback and upgrades

The built-in English messages are the fallback layer. Russian messages override English when `ru` is active. A custom locale overrides English only for the keys it defines; missing custom translations remain in English.

KSL layers the locale files packaged in the JAR underneath the files in the plugin data directory. Existing translations therefore keep priority, while new message keys from an updated JAR become available automatically. This avoids overwriting a server owner's translations during an upgrade.

If a requested locale file does not exist, KSL falls back to Russian when the built-in Russian file is available, otherwise to English. The `template.yml` file is a reference for translators and is not listed as an active language.
