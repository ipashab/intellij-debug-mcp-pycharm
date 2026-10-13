# Debug MCP Server — PyCharm

MCP-сервер (Model Context Protocol), встроенный в **PyCharm**, который даёт AI-агентам
(OpenCode, Claude Code, Cursor, Windsurf и любым другим MCP-клиентам) полный контроль над
отладчиком PyCharm: брейкпоинты, степпинг, переменные, evaluate, консоль.

Порт плагина [sahil-ramagiri/intellij-debug-mcp](https://github.com/sahil-ramagiri/intellij-debug-mcp)
(IntelliJ IDEA, Java/Kotlin) под **PyCharm 2026.1**: из оригинала вырезана жёсткая зависимость
от Java-плагина (`com.intellij.java`), из-за которой он вообще не загружался в PyCharm.
Инструменты переведены на generic XDebugger API — работают с Python (и любыми другими
языками, поддерживаемыми IDE).

## Возможности — 18 инструментов

| Группа | Инструменты |
|---|---|
| Run-конфигурации | `list_run_configurations`, `start_debug_session`, `stop_debug_session`, `get_debug_status` |
| Брейкпоинты | `set_breakpoint` (с условием), `remove_breakpoint`, `list_breakpoints` |
| Степпинг | `resume`, `step_over`, `step_into`, `step_out` |
| Инспекция | `get_threads`, `get_stack_trace`, `get_variables`, `evaluate_expression`, `get_console_output`, `send_console_input` |
| Процесс | `get_process_info` |

Все инструменты принимают опциональный `sessionName` (цель в конкретную сессию из `get_debug_status`)
и опциональный `port` (маршрутизация в конкретный инстанс IDE — полезно при нескольких открытых окнах).

## Установка

Готовый собранный плагин: **[Releases](../../releases)** → `debug-mcp-pycharm-<version>.zip`.

1. PyCharm → Settings → Plugins → ⚙ → **Install Plugin from Disk…** → выберите zip.
2. Перезапустите PyCharm. Сервер стартует автоматически при открытии проекта.

Порт (режим Dynamic — по умолчанию) пишется в `<проект>/.idea/debugmcp.port`,
tool window **Debug MCP** (нижняя панель) показывает порт и готовый конфиг для MCP-клиента.

## Настройка MCP-клиентов

Внутри каждого инструмента есть параметр `port`; но проще использовать bundled stdio-прокси —
он сам находит порт через `.idea/debugmcp.port` и переподключается при перезапуске IDE.
(Учтите: при нескольких открытых окнах PyCharm прокси возьмёт порт последнего активного
проекта; для конкретного окна передавайте `port` явно.)

### OpenCode / OpenChamber

`opencode.json` (глобальный `~/.config/opencode/opencode.json` или проекта `.opencode/opencode.json`):

```json
{
  "mcp": {
    "pycharm-debug": {
      "type": "local",
      "command": [
        "/Applications/PyCharm.app/Contents/jbr/Contents/Home/bin/java",
        "-jar",
        "<plugin-dir>/lib/intellij-debug-mcp-proxy.jar"
      ],
      "enabled": true
    }
  }
}
```

`<plugin-dir>` на macOS:
`~/Library/Application Support/JetBrains/PyCharm2026.1/plugins/intellij-debug-mcp-plugin`

При нескольких проектах порт можно жёстко задать в tool window **Debug MCP**
(режим Fixed, например `6274`) и ходить напрямую.

### Claude Code / Cursor / Windsurf (stdio)

```json
{
  "mcpServers": {
    "pycharm-debug": {
      "command": "<plugin-dir>/bin/debugmcp-proxy.cmd"
    }
  }
}
```

> На macOS после установки проверьте, что `bin/debugmcp-proxy.cmd` не содержит CRLF
> (`file` должен показать `POSIX shell script`); при необходимости: `sed -i '' 's/\r$//' <файл>`.

### Прямое TCP-подключение

Для клиентов с нативной поддержкой сокетов:

```
Host: 127.0.0.1
Port: <из tool window Debug MCP или .idea/debugmcp.port>
Protocol: newline-delimited JSON-RPC (MCP)
```

## Использование (типовой сценарий)

1. `set_breakpoint(filePath, line)` — поставить брейкпоинт (filePath — абсолютный путь).
2. `start_debug_session(configName)` — запуск run-конфигурации в debug.
3. `get_debug_status` — сессия `suspended`? Вы на брейкпоинте.
4. `evaluate_expression` / `get_variables` / `get_stack_trace` — инспекция.
5. `step_over` / `step_into` / `resume` — управление.
6. `stop_debug_session` — завершение.

Прогон из агента:
```
«Поставь брейкпоинт на строку 246 test_main_page.py, запусти в дебаге
конфиг test_rail_shown_on_tv и покажи значение rail_tv_channels»
```

## Сборка из исходников

Требуется JDK 17+, Maven 3.8+, PyCharm 2026.1.

```bash
mvn -Dintellij.home=/Applications/PyCharm.app/Contents clean package
```

Артефакт: `target/intellij-debug-mcp-plugin-<version>-plugin.zip`.

## Отличия от upstream

- Убрана зависимость `com.intellij.java` — плагин загружается в PyCharm.
- Удалён `decompile_class` (Java-специфика).
- `set_breakpoint`/`remove_breakpoint`: только `filePath` (было ещё `className` для JAR-классов).
- Убран JDI-lookup потоков/JVM-инфо из `get_threads`/`get_process_info` (Java-only).
- `isCompiling` в `get_debug_status` — заглушка `false` (Python не компилируется).
- runtime patch: полиглот-лаунчер `bin/debugmcp-proxy.cmd` без CRLF.

## Известные ограничения

- Строковые значения в `evaluate_expression` обрезаются презентером (~100 символов).
  Обход: доставать значения чанками (`expr[a:b]`) или короткими выражениями по полям.
- `idea-version` ограничен `261.*` — на мажорных обновлениях PyCharm пересоберите
  с расширенным диапазоном.

## Лицензия

MIT — как и upstream. Оригинал: [sahil-ramagiri/intellij-debug-mcp](https://github.com/sahil-ramagiri/intellij-debug-mcp).
