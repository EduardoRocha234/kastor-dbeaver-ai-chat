# DBeaver AI Chat — Design Spec

Data: 2026-10-13
Status: aguardando revisão

## 1. Objetivo

Chat de IA dentro do DBeaver, no estilo Copilot Chat do VS Code. O chat acessa as conexões e schemas que o usuário já tem configurados no DBeaver e usa um modelo que o próprio usuário configura.

Público: primeiro uso pessoal/estudo, depois o time, depois o público geral. Por isso o código e o empacotamento já devem servir para distribuição (update site p2), mas o MVP não precisa ter recursos de governança.

### Critérios de sucesso do MVP
1. Usuário instala o plugin em qualquer DBeaver CE 24.1.0 ou mais novo e abre a view "AI Chat".
2. Configura um provedor (OpenAI-compatible ou Anthropic) com URL, modelo e chave.
3. Pergunta em linguagem natural sobre uma conexão; a IA lista schemas e tabelas, descreve tabelas e roda SELECT sem pedir permissão.
4. Quando a IA quer escrever (DML/DDL), o chat mostra o SQL e a conexão, e só executa se o usuário aprovar.
5. As respostas chegam em streaming. Blocos SQL têm botão para inserir no editor SQL.
6. O chat usa a conexão, o schema e o SQL selecionado do editor ativo como contexto.
7. As conversas continuam lá depois de reiniciar o DBeaver.

### Fora do escopo do MVP
- Servidor MCP embutido.
- Adaptadores nativos além de OpenAI-compatible e Anthropic (Gemini, Bedrock etc.).
- Modo de auto-aprovação de escrita.
- Políticas centralizadas para o time (allowlist de conexões, auditoria).
- Suporte a versões do DBeaver anteriores à 24.1.

## 2. Ambiente alvo e compatibilidade

Requisito: funcionar no DBeaver CE **24.1.0 e em todas as versões posteriores** (24.2, 24.3, 25.x...). Versão de desenvolvimento local: 24.1.4.

- **Linha de base de compilação:** compila contra o DBeaver 24.1.x, a versão mais antiga suportada. Assim só entram APIs que já existem na 24.1, e o bytecode é Java 17 (`Bundle-RequiredExecutionEnvironment: JavaSE-17`). Esse bytecode roda nas versões novas que trazem JRE 21+.
- **Faixas no MANIFEST:** `Require-Bundle` só com limite inferior (versão do bundle na 24.1.0) e sem limite superior. Nenhum bundle é amarrado a uma versão exata.
- **Dependências:** usa só bundles presentes em todas as versões (`org.jkiss.dbeaver.model`, `.core`, `.ui`, `.ui.editors.sql`, `org.eclipse.ui`, `org.eclipse.swt`, `com.google.gson`). Bibliotecas próprias (`commonmark-java`) ficam embutidas no jar (`Bundle-ClassPath`), sem `Import-Package` delas, para não colidir com versões que o DBeaver venha a trazer.
- **Superfície de API:** só APIs públicas e estáveis do modelo (`org.jkiss.dbeaver.model.*`, `DBWorkbench`, `DBUtils`, `SQLEditor` público). Pacotes `*.internal.*` ficam proibidos. Tudo o que é mais frágil entre versões (SQLEditor, parser SQL, abrir novo editor) passa por uma camada `compat/` com `DBeaverCompat`. Lá ficam as chamadas sensíveis, com fallback: se o método não existir, cai num caminho mais simples, como classificar SQL por um tokenizer próprio ou só copiar o SQL para a área de transferência.
- **Coexistência:** o DBeaver 24.x+ tem o próprio AI Assistant (`org.jkiss.dbeaver.model.ai`). O plugin não depende dele e usa IDs próprios (view, comandos, preferências, atalhos) para não conflitar.
- **Reutilizado do DBeaver:** `com.google.gson` e SWT Browser (WebView2 no Windows, WebKit no Linux/macOS).
- **Build:** Maven Tycho, Java 17. Target platform com perfis: `dbeaver-24.1` (padrão, instalação local ou p2 `https://dbeaver.io/update/24.1.0/`) e `dbeaver-latest` (p2 `https://dbeaver.io/update/latest/`), para checar compilação contra a versão mais nova.

## 3. Arquitetura

Um bundle OSGi (`io.github.eduardo.dbeaver.aichat`), uma feature e um update site.

```
provider/  -> conversa com o LLM (HTTP + SSE), sem dependência do DBeaver
tools/     -> ferramentas de banco expostas ao modelo (API DBeaver)
agent/     -> loop modelo <-> tools, aprovação de escrita
context/   -> contexto do editor SQL ativo
compat/    -> isola chamadas de API do DBeaver que variam entre versões
history/   -> persistência das conversas
ui/        -> view, renderer HTML, preferências
```

As dependências seguem uma só direção: `ui -> agent -> (provider, tools, context, history)`. `provider` não conhece DBeaver, então pode ser testado em JUnit puro.

### 3.1 Provider
- `LlmProvider.stream(ChatRequest, Consumer<StreamEvent>, CancellationToken)`.
- `StreamEvent`: `TextDelta`, `ToolCallStart`, `ToolCallArgsDelta`, `ToolCallEnd`, `Done(stopReason, usage)`, `Error`.
- Modelo interno: `ChatMessage(role, content parts)`, `ToolCall(id, name, argsJson)`, `ToolResult(id, content, isError)`, `ToolSpec(name, description, jsonSchema)`.
- `OpenAiCompatibleProvider`: `POST {baseUrl}/chat/completions`, `stream: true`, `tools`, tratando os deltas de `tool_calls` por índice.
- `AnthropicProvider`: `POST {baseUrl}/v1/messages`, headers `x-api-key` e `anthropic-version`, eventos `content_block_start/delta/stop` com `tool_use` e `input_json_delta`.
- Transporte: `java.net.http.HttpClient` mais um `SseParser` próprio.
- Configuração (`ProviderConfig`): id, nome, tipo, baseUrl, modelo, maxTokens, temperatura. A apiKey fica no Eclipse Secure Storage, indexada pelo id. Os demais campos ficam no preference store do bundle, em JSON.

### 3.2 Tools
Interface `DbTool { name(); description(); jsonSchema(); ToolOutcome execute(JsonObject args, ToolContext ctx) }`.

| Tool | Args | Comportamento |
|---|---|---|
| `list_connections` | — | nome, id, driver, conectado?, read-only? |
| `list_schemas` | connection | catálogos e schemas (conecta se necessário) |
| `list_tables` | connection, schema, filtro? | tabelas e views, até 500 itens |
| `describe_table` | connection, schema, table | colunas (tipo, nullable, default), PK, FKs, índices |
| `run_query` | connection, sql, maxRows? | só leitura; até 200 linhas; resultado em texto tabular truncado em ~16 KB |
| `execute_write` | connection, sql, reason | precisa de aprovação; retorna linhas afetadas |

APIs do DBeaver usadas:
- Registro: `DBWorkbench.getPlatform().getWorkspace().getProjects()` e `DBPProject.getDataSourceRegistry()`.
- Conectar: `DBPDataSourceContainer.connect(monitor, true, true)`.
- Metadados: `DBSObjectContainer.getChildren`, `DBSEntity.getAttributes`, `DBSEntity.getAssociations`, `DBSTable.getIndexes` e `DBUtils.findObject`.
- Execução: `DBUtils.getDefaultContext` mais `DBCExecutionContext.openSession`, `prepareStatement`, `setLimit` e timeout.

`SqlClassifier`: usa o parser do DBeaver (`SQLQuery.isModifying()` / tipo do statement) para classificar o SQL em `READ` ou `WRITE`. Regras:
- Vários statements ou parse falho: `WRITE` (fail-safe).
- `run_query` recebendo `WRITE`: recusa com erro e orienta o modelo a usar `execute_write`.
- Conexão read-only no DBeaver: `execute_write` sempre recusa.
- `run_query` também roda numa transação que é desfeita no final, quando o driver permite, como segunda camada de proteção.

### 3.3 Agent
- `ChatSession` guarda a conversa, o provedor, a conexão padrão e as tools. Cada turno roda num `org.eclipse.core.runtime.jobs.Job`.
- Loop: envia o pedido, faz stream para a UI, junta as tool calls, executa as tools, anexa os resultados e repete. Para com resposta final, com até 10 iterações ou quando o usuário cancela.
- `ApprovalGate.request(ApprovalRequest) : CompletableFuture<Decision>`. A UI mostra o card de aprovação e o job aguarda. Se o usuário rejeitar, o modelo recebe `ToolResult(isError=true, "usuário rejeitou")`.
- Botão Parar: cancela o job, a request HTTP (`CancellationToken`) e a execução SQL em andamento (`DBCStatement.cancelBlock`).
- `SystemPromptBuilder`: papel, dialeto SQL da conexão (`SQLDialect.getDialectName()`), conexão e schema padrão, SQL selecionado no editor e as regras de leitura/escrita.

### 3.4 Contexto do editor
`EditorContextResolver` lê o `SQLEditor` ativo: `getDataSourceContainer()`, o contexto/schema padrão e a seleção. O resultado vira um chip na view ("prod-pg / public · 12 linhas selecionadas"), que o usuário pode remover. O seletor de conexão da view começa com a conexão do editor ativo.

### 3.5 UI
- Posição e acesso: o painel fica acoplado à direita da área de editores na perspectiva padrão do DBeaver (`org.jkiss.dbeaver.core.perspective`), como o chat do VS Code. Um botão "AI Chat" na barra de ferramentas principal e o item **Window → AI Chat** abrem ou focam o painel.
- `ChatView` (ViewPart, categoria DBeaver):
  - Barra superior: provedor/modelo, conexão e os botões nova conversa / histórico.
  - Centro: `Browser` SWT com `chat.html` + CSS + JS embutidos no bundle.
  - Rodapé: `Text` multilinha, Enviar (Ctrl+Enter) e Parar.
- Markdown renderizado no Java com `commonmark-java` (embutido no bundle). O HTML vai para o browser por `browser.execute("appendDelta(...)")`, com strings escapadas.
- Para evitar repintar a cada token, o streaming junta os deltas a cada ~50 ms com `Display.asyncExec`.
- Callbacks JS para Java via `BrowserFunction`: `approve(id)`, `reject(id)`, `insertSql(text)`, `openSql(text)`, `copy(text)`.
- Tool calls aparecem como cards colapsáveis (nome, args e resultado resumido).
- `ProviderPreferencePage` (Window > Preferences > AI Chat): CRUD de provedores, botão "Testar conexão", limites de linhas/tamanho e aviso de que resultados de consultas são enviados ao provedor.
- Comando "Ask AI about selection" no menu de contexto do editor SQL: abre a view com a seleção como contexto.
- Inserir SQL: insere no cursor do `SQLEditor` ativo (pelo `IDocument`). Sem editor ativo, abre um novo editor SQL na conexão atual.

### 3.6 Histórico
- `ConversationStore` grava um JSON por conversa em `<workspace>/.metadata/.plugins/io.github.eduardo.dbeaver.aichat/conversations/<uuid>.json`.
- Conteúdo: título (primeira pergunta, truncada), datas, provedor, id da conexão, mensagens e tool calls/results.
- Salva ao fim de cada turno. A lista mostra as conversas da mais recente para a mais antiga, com opção de abrir e apagar.

## 4. Tratamento de erros
- Erros HTTP (401, 429, 5xx) aparecem como mensagem de erro no chat com o texto do provedor. Não há retry automático no MVP.
- Uma exceção numa tool vira `ToolResult(isError=true, mensagem)` e o modelo decide como seguir.
- Falha ao conectar no banco: o resultado de erro informa a conexão e a causa.
- Exceções inesperadas vão para o log do Eclipse (`Log.error`) e aparecem como erro no chat. A view nunca deve quebrar.

## 5. Segurança
- Toda escrita passa por aprovação explícita. Não existe bypass no MVP.
- Fail-safe na classificação: na dúvida, o SQL é tratado como escrita.
- Chaves de API ficam no Secure Storage. Nunca vão para logs ou para o histórico.
- Resultados de SELECT são truncados (linhas e bytes) antes de ir ao provedor, e o usuário é avisado nas preferências.
- O renderer escapa todo conteúdo vindo do modelo ou do banco antes de inserir no DOM. O HTML gerado pelo commonmark tem HTML bruto desativado (`escapeHtml(true)`).

## 6. Testes
- Unitários (JUnit 5, sem OSGi): `SseParser`, os dois providers contra servidor HTTP fake (`com.sun.net.httpserver`), `SqlClassifier` (com o parser do DBeaver no classpath de teste), `ChatSession` com provider fake roteirizado e tools fake, `ConversationStore`.
- Manuais E2E: roteiro na seção Verificação do plano (Ollama ou OpenAI, conexão SQLite/Postgres de teste).
- Matriz de compatibilidade:
  - Compilação: `mvn verify -P dbeaver-24.1` e `mvn verify -P dbeaver-latest` precisam passar (CI no GitHub Actions quando houver repositório remoto).
  - Smoke test manual (instalar, abrir a view, uma pergunta com tool e uma aprovação de escrita): DBeaver 24.1.x (mínimo) e a última versão estável.
  - Testes unitários de `DBeaverCompat` cobrindo os fallbacks.

## 7. Empacotamento e instalação
- `mvn clean verify` gera o bundle jar, a feature e o update site (`releng/update-site/target/repository`).
- Desenvolvimento: copiar o jar para `C:\Program Files\DBeaver\dropins\` e reiniciar com `-clean`.
- Distribuição: update site (zip ou URL) via Help > Install New Software. A feature declara `org.jkiss.dbeaver.core` com faixa `[24.1.0,)`, então o p2 recusa a instalação em versões anteriores à 24.1.0.
