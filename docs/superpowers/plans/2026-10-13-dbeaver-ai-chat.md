# DBeaver AI Chat — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Plugin nativo para DBeaver CE ≥ 24.1.0 com uma view "AI Chat" em que um LLM configurável (OpenAI-compatible ou Anthropic) consulta metadados, roda SELECT e — com aprovação explícita — executa escrita nas conexões do DBeaver.

**Architecture:** Toda a lógica que não depende do DBeaver (clientes LLM com SSE, loop agêntico, ferramentas, classificador SQL, histórico, markdown) fica no módulo Maven puro `core`, testado com JUnit 5. O bundle OSGi (Tycho) embute o jar do `core` em `lib/` e implementa só a ponte com o DBeaver (`DBeaverGateway`, `DBeaverCompat`, contexto do editor) e a UI SWT (view com `Browser`, página de preferências). Feature + update site p2 para instalação.

**Tech Stack:** Java 17, Maven 3.9.9, Tycho 4.0.10, JUnit 5.11, Gson 2.11 (fornecido pelo DBeaver), commonmark-java 0.24 (embutido), SWT/JFace/Eclipse UI, API `org.jkiss.dbeaver.*`, `java.net.http.HttpClient`.

**Spec:** `docs/superpowers/specs/2026-10-13-dbeaver-ai-chat-design.md`

## Global Constraints

- Compatibilidade: DBeaver CE **24.1.0 e todas as versões posteriores**; compilar contra 24.1.x; bytecode Java 17 (`Bundle-RequiredExecutionEnvironment: JavaSE-17`).
- `Require-Bundle` sem limite superior de versão; o piso fica na feature: `org.jkiss.dbeaver.core` `version="24.1.0" match="greaterOrEqual"`.
- Proibido usar pacotes `*.internal.*`. Chamadas frágeis entre versões (parser SQL do DBeaver, abrir editor SQL) só dentro de `DBeaverCompat`, sempre com fallback (`catch (RuntimeException | LinkageError e)`).
- `commonmark` e `aichat-core` embutidos em `lib/` via `Bundle-ClassPath`; Gson vem do bundle `com.google.gson` do DBeaver.
- Todos os IDs (bundle, view, comandos, preferências) com prefixo `io.github.eduardo.dbeaver.aichat`.
- Escrita (DML/DDL) só via `execute_write` com aprovação explícita no chat; classificação fail-safe (dúvida = escrita); conexões read-only do DBeaver bloqueiam escrita.
- Chaves de API só no Eclipse Secure Storage; nunca em preferências, logs ou histórico.
- Textos de UI em português (pt-BR).
- Anthropic: `anthropic-version: 2023-06-01`; modelo padrão do preset `claude-opus-5-5`; `max_tokens` padrão 64000; nunca enviar `temperature`; `fallbacks: "default"` + header `anthropic-beta: server-side-fallback-2026-07-01` quando `serverFallback=true`, com nova tentativa automática sem fallback se a API responder 400 mencionando "fallback"; blocos de conteúdo do assistente (incluindo `thinking` com `signature`) devolvidos à API sem alteração.
- Transporte HTTP próprio (`java.net.http`) para os dois provedores, em vez do SDK Java da Anthropic: o SDK puxa OkHttp/Jackson/Kotlin, que teriam de ser embutidos no bundle e podem colidir com versões carregadas pelo DBeaver; um transporte SSE único atende OpenAI-compatible e Anthropic.

Ajustes em relação à spec (decididos no plano):
- O provider expõe só deltas de texto + um `AssistantTurn` final (em vez de eventos `ToolCallStart/ArgsDelta/End`); a UI não precisa de argumentos parciais.
- `eager_input_streaming` fica desligado: os argumentos das tools são pequenos (SQL) e a URL base pode apontar para proxies.
- Os "perfis" `dbeaver-24.1`/`dbeaver-latest` viram `scripts/verify.sh 24.1.0|latest`: o repositório p2 versionado do DBeaver não existe (só `update/latest`), então a target platform aponta para uma instalação Linux baixada do `dbeaver.io/files`.

## Review Focus

1. **Turno interrompido com `tool_use` pendente** (cancelamento durante aprovação, `max_tokens`, recusa): o histórico precisa ganhar um `tool_result` para cada chamada, senão a próxima pergunta recebe 400 da Anthropic. Testado em `ChatSessionTest` (Task 7).
2. **SQL que parece leitura mas escreve** (comentário executável MySQL `/*! */`, `--` sem espaço, vários comandos, CTE com DELETE, `SELECT ... INTO`, `FOR UPDATE`, `EXPLAIN ANALYZE`): deve virar escrita. Testado em `SqlClassifierTest` (Task 5).
3. **Resultados grandes ou estranhos** (milhares de linhas, células enormes, quebras de linha, NULL, binário): truncar antes de mandar ao modelo e à UI. Testado em `TextTableTest`/`ToolsTest` (Task 6); binário em `DBeaverGateway.cell` (Task 10).
4. **Conteúdo hostil vindo do modelo ou do banco** (HTML, `<script>`, links `javascript:`, imagem markdown apontando para URL externa = exfiltração): escapar no markdown e bloquear rede por CSP. Testado em `MarkdownRendererTest` (Task 9); CSP verificado no E2E (Task 13).
5. **Trocar de provedor no meio da conversa** (histórico com blocos `thinking` da Anthropic indo para OpenAI e vice-versa): cada mapper usa o bruto só quando é do seu formato. Testado em `OpenAiCompatibleProviderTest` e `AnthropicProviderTest` (Tasks 3 e 4).

## File Map

```
.gitignore
.mvn/maven.config
pom.xml                                   (aggregator + Tycho config)
scripts/install-toolchain.sh              (JDK 17 + Maven em ~/.local/opt)
scripts/env.sh                            (JAVA_HOME/PATH)
scripts/fetch-dbeaver.sh                  (baixa DBeaver Linux, gera target)
scripts/verify.sh                         (build+testes contra uma versão)
scripts/make-icon.py                      (gera os ícones PNG do chat)
releng/target/dbeaver.target.template
releng/update-site/{pom.xml,category.xml}
features/io.github.eduardo.dbeaver.aichat.feature/{pom.xml,feature.xml,build.properties}
core/pom.xml
core/src/main/java/io/github/eduardo/dbeaver/aichat/core/
  Json.java CancellationToken.java CancelledException.java
  llm/   Role ToolCall ToolSpec ChatMessage ChatRequest AssistantTurn StopReason
         ProviderType ProviderConfig LlmProvider LlmException StreamErrorException
         SseParser HttpStreaming OpenAiMapper OpenAiStreamAccumulator OpenAiCompatibleProvider
         AnthropicMapper AnthropicStreamAccumulator AnthropicProvider ProviderFactory
  sql/   SqlClassifier
  tools/ DatabaseGateway GatewayException ConnectionInfo TableInfo ColumnInfo ForeignKeyInfo
         IndexInfo TableDescription QueryResult ToolException ToolOutcome ToolLimits ToolContext
         ApprovalGate ApprovalRequest Decision DbTool Args ConnectionResolver TextTable ToolRegistry
         ListConnectionsTool ListSchemasTool ListTablesTool DescribeTableTool RunQueryTool ExecuteWriteTool
  agent/ Conversation SessionSettings SessionListener ChatSession PromptContext SystemPromptBuilder
  history/ ConversationSummary ConversationStore
  render/  MarkdownRenderer
core/src/test/java/... (um *Test por unidade + FakeHttpServer, FakeGateway, ScriptedProvider)
bundles/io.github.eduardo.dbeaver.aichat/
  pom.xml META-INF/MANIFEST.MF build.properties plugin.xml
  resources/chat/{chat.html,chat.css,chat.js}
  src/io/github/eduardo/dbeaver/aichat/
    Activator.java
    dbeaver/ DBeaverGateway DBeaverCompat EditorContext EditorContextResolver
    settings/ ProviderSettings ProviderPresets ProviderDialog AiChatPreferencePage
    ui/ ChatView ChatBrowserBridge UiApprovalGate OpenChatHandler AskAiHandler
  icons/{chat.png,chat@2x.png}             (gerados por scripts/make-icon.py)
README.md
```

Convenção de commits: mensagens no formato Conventional Commits, sempre com o trailer `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` (segundo `-m`).

---

### Task 0: Toolchain, repositório e scripts

**Files:**
- Create: `.gitignore`, `scripts/install-toolchain.sh`, `scripts/env.sh`, `scripts/fetch-dbeaver.sh`, `scripts/verify.sh`, `releng/target/dbeaver.target.template`

**Interfaces:**
- Produces: `. scripts/env.sh` deixa `java`/`mvn` no PATH; `scripts/fetch-dbeaver.sh <versão>` gera `releng/target/dbeaver.target`; `scripts/verify.sh <versão>` roda `mvn clean verify`.

- [ ] **Step 1: Inicializar git**

```bash
cd /home/eduardo/projetos-pessoais/teste
git init -b main
```

- [ ] **Step 2: Criar `.gitignore`**

```gitignore
target/
.dbeaver/
releng/target/dbeaver.target
bundles/*/lib/
.idea/
.classpath
.project
.settings/
*.iml
```

- [ ] **Step 3: Criar `scripts/install-toolchain.sh`**

```bash
#!/usr/bin/env bash
# Installs JDK 17 (Temurin) and Maven 3.9.9 under ~/.local/opt, no sudo needed.
set -euo pipefail
OPT="$HOME/.local/opt"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$OPT"
if [ ! -x "$OPT/jdk-17/bin/java" ]; then
  curl -fsSL -o "$TMP/jdk.tar.gz" "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  mkdir -p "$OPT/jdk-17"
  tar -xzf "$TMP/jdk.tar.gz" -C "$OPT/jdk-17" --strip-components=1
fi
if [ ! -x "$OPT/maven/bin/mvn" ]; then
  curl -fsSL -o "$TMP/maven.tar.gz" "https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz"
  mkdir -p "$OPT/maven"
  tar -xzf "$TMP/maven.tar.gz" -C "$OPT/maven" --strip-components=1
fi
"$OPT/jdk-17/bin/java" -version
JAVA_HOME="$OPT/jdk-17" "$OPT/maven/bin/mvn" -v
```

- [ ] **Step 4: Criar `scripts/env.sh`**

```bash
# Source this file: . scripts/env.sh
export JAVA_HOME="$HOME/.local/opt/jdk-17"
export PATH="$JAVA_HOME/bin:$HOME/.local/opt/maven/bin:$PATH"
```

- [ ] **Step 5: Criar `releng/target/dbeaver.target.template`**

```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<?pde version="3.8"?>
<target name="dbeaver">
  <locations>
    <location path="@DBEAVER_HOME@/plugins" type="Directory"/>
  </locations>
</target>
```

- [ ] **Step 6: Criar `scripts/fetch-dbeaver.sh`**

```bash
#!/usr/bin/env bash
# Downloads a DBeaver CE Linux build and points releng/target/dbeaver.target at it.
# Usage: scripts/fetch-dbeaver.sh 24.1.0   |   scripts/fetch-dbeaver.sh latest
set -euo pipefail
VERSION="${1:-24.1.0}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/.dbeaver/$VERSION"
if [ ! -d "$DEST/dbeaver/plugins" ]; then
  if [ "$VERSION" = "latest" ]; then
    URL="https://dbeaver.io/files/dbeaver-ce-latest-linux.gtk.x86_64.tar.gz"
  else
    URL="https://dbeaver.io/files/$VERSION/dbeaver-ce-$VERSION-linux.gtk.x86_64-nojdk.tar.gz"
  fi
  mkdir -p "$DEST"
  curl -fSL -o "$DEST/dbeaver.tar.gz" "$URL"
  tar -xzf "$DEST/dbeaver.tar.gz" -C "$DEST"
  rm "$DEST/dbeaver.tar.gz"
fi
sed "s#@DBEAVER_HOME@#$DEST/dbeaver#" "$ROOT/releng/target/dbeaver.target.template" > "$ROOT/releng/target/dbeaver.target"
grep -m1 '^version=' "$DEST/dbeaver/.eclipseproduct"
```

- [ ] **Step 7: Criar `scripts/verify.sh`**

```bash
#!/usr/bin/env bash
# Builds and runs all tests against one DBeaver version: scripts/verify.sh 24.1.0|latest
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
. "$ROOT/scripts/env.sh"
"$ROOT/scripts/fetch-dbeaver.sh" "${1:-24.1.0}"
cd "$ROOT"
mvn clean verify
```

- [ ] **Step 8: Instalar toolchain e baixar DBeaver 24.1.0**

```bash
chmod +x scripts/*.sh
scripts/install-toolchain.sh
scripts/fetch-dbeaver.sh 24.1.0
ls .dbeaver/24.1.0/dbeaver/plugins | grep -E '^(org.jkiss.dbeaver.core_|com.google.gson_)'
```

Expected: `openjdk version "17...`, `Apache Maven 3.9.9`, `version=24.1.0`, e as linhas `org.jkiss.dbeaver.core_24.1.0...jar` e `com.google.gson_...jar`. Se o tar não extrair para `dbeaver/`, ajuste `DEST/dbeaver` no script para o nome real da pasta.

- [ ] **Step 9: Commit**

```bash
git add .gitignore scripts releng/target/dbeaver.target.template docs
git commit -m "chore: toolchain scripts and DBeaver target template" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 1: Esqueleto Maven/Tycho (core + bundle + feature + update site)

**Files:**
- Create: `.mvn/maven.config`, `pom.xml`, `core/pom.xml`, `bundles/io.github.eduardo.dbeaver.aichat/{pom.xml,META-INF/MANIFEST.MF,build.properties,plugin.xml}`, `bundles/io.github.eduardo.dbeaver.aichat/src/io/github/eduardo/dbeaver/aichat/Activator.java`, `features/io.github.eduardo.dbeaver.aichat.feature/{pom.xml,feature.xml,build.properties}`, `releng/update-site/{pom.xml,category.xml}`

**Interfaces:**
- Consumes: `releng/target/dbeaver.target` (Task 0).
- Produces: `Activator.PLUGIN_ID = "io.github.eduardo.dbeaver.aichat"`, `Activator.getDefault()`; artefato Maven `io.github.eduardo.dbeaver.aichat:aichat-core:0.1.0-SNAPSHOT`; o bundle carrega `lib/aichat-core.jar`, `lib/commonmark.jar`, `lib/commonmark-ext-gfm-tables.jar`.

- [ ] **Step 1: Criar `.mvn/maven.config`**

```
--no-transfer-progress
```

- [ ] **Step 2: Criar `pom.xml` (raiz)**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.github.eduardo.dbeaver.aichat</groupId>
  <artifactId>aichat-parent</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <properties>
    <tycho.version>4.0.10</tycho.version>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  </properties>

  <modules>
    <module>core</module>
    <module>bundles/io.github.eduardo.dbeaver.aichat</module>
    <module>features/io.github.eduardo.dbeaver.aichat.feature</module>
    <module>releng/update-site</module>
  </modules>

  <build>
    <plugins>
      <plugin>
        <groupId>org.eclipse.tycho</groupId>
        <artifactId>tycho-maven-plugin</artifactId>
        <version>${tycho.version}</version>
        <extensions>true</extensions>
      </plugin>
      <plugin>
        <groupId>org.eclipse.tycho</groupId>
        <artifactId>target-platform-configuration</artifactId>
        <version>${tycho.version}</version>
        <configuration>
          <target>
            <file>${maven.multiModuleProjectDirectory}/releng/target/dbeaver.target</file>
          </target>
          <pomDependencies>ignore</pomDependencies>
          <environments>
            <environment>
              <os>linux</os>
              <ws>gtk</ws>
              <arch>x86_64</arch>
            </environment>
          </environments>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: Criar `core/pom.xml`** (sem parent: o `core` é Maven puro, fora do Tycho)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>io.github.eduardo.dbeaver.aichat</groupId>
  <artifactId>aichat-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.release>17</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <commonmark.version>0.24.0</commonmark.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>com.google.code.gson</groupId>
      <artifactId>gson</artifactId>
      <version>2.11.0</version>
      <scope>provided</scope>
    </dependency>
    <dependency>
      <groupId>org.commonmark</groupId>
      <artifactId>commonmark</artifactId>
      <version>${commonmark.version}</version>
    </dependency>
    <dependency>
      <groupId>org.commonmark</groupId>
      <artifactId>commonmark-ext-gfm-tables</artifactId>
      <version>${commonmark.version}</version>
    </dependency>
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>5.11.3</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.13.0</version>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.5.2</version>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 4: Criar `bundles/io.github.eduardo.dbeaver.aichat/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>io.github.eduardo.dbeaver.aichat</groupId>
    <artifactId>aichat-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <relativePath>../../pom.xml</relativePath>
  </parent>
  <artifactId>io.github.eduardo.dbeaver.aichat</artifactId>
  <packaging>eclipse-plugin</packaging>

  <dependencies>
    <!-- Declared only so the reactor builds core first; the jar is embedded below. -->
    <dependency>
      <groupId>io.github.eduardo.dbeaver.aichat</groupId>
      <artifactId>aichat-core</artifactId>
      <version>${project.version}</version>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-dependency-plugin</artifactId>
        <version>3.8.1</version>
        <executions>
          <execution>
            <id>embed-libs</id>
            <phase>initialize</phase>
            <goals>
              <goal>copy</goal>
            </goals>
            <configuration>
              <outputDirectory>${project.basedir}/lib</outputDirectory>
              <stripVersion>true</stripVersion>
              <overWriteSnapshots>true</overWriteSnapshots>
              <artifactItems>
                <artifactItem>
                  <groupId>io.github.eduardo.dbeaver.aichat</groupId>
                  <artifactId>aichat-core</artifactId>
                  <version>${project.version}</version>
                </artifactItem>
                <artifactItem>
                  <groupId>org.commonmark</groupId>
                  <artifactId>commonmark</artifactId>
                  <version>0.24.0</version>
                </artifactItem>
                <artifactItem>
                  <groupId>org.commonmark</groupId>
                  <artifactId>commonmark-ext-gfm-tables</artifactId>
                  <version>0.24.0</version>
                </artifactItem>
              </artifactItems>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 5: Criar `bundles/io.github.eduardo.dbeaver.aichat/META-INF/MANIFEST.MF`** (terminar com linha em branco)

```
Manifest-Version: 1.0
Bundle-ManifestVersion: 2
Bundle-Name: DBeaver AI Chat
Bundle-SymbolicName: io.github.eduardo.dbeaver.aichat;singleton:=true
Bundle-Version: 0.1.0.qualifier
Bundle-Vendor: Eduardo
Bundle-Activator: io.github.eduardo.dbeaver.aichat.Activator
Bundle-ActivationPolicy: lazy
Bundle-RequiredExecutionEnvironment: JavaSE-17
Automatic-Module-Name: io.github.eduardo.dbeaver.aichat
Bundle-ClassPath: .,
 lib/aichat-core.jar,
 lib/commonmark.jar,
 lib/commonmark-ext-gfm-tables.jar
Require-Bundle: org.eclipse.core.runtime,
 org.eclipse.core.commands,
 org.eclipse.equinox.security,
 org.eclipse.ui,
 org.eclipse.jface.text,
 org.eclipse.ui.workbench.texteditor,
 com.google.gson,
 org.jkiss.dbeaver.model,
 org.jkiss.dbeaver.ui,
 org.jkiss.dbeaver.ui.editors.base,
 org.jkiss.dbeaver.ui.editors.sql

```

- [ ] **Step 6: Criar `build.properties` e `plugin.xml` do bundle**

`bundles/io.github.eduardo.dbeaver.aichat/build.properties`:

```properties
source.. = src/
output.. = target/classes/
bin.includes = META-INF/,\
               .,\
               plugin.xml,\
               lib/
```

`bundles/io.github.eduardo.dbeaver.aichat/plugin.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<?eclipse version="3.4"?>
<plugin>
</plugin>
```

- [ ] **Step 7: Criar `Activator.java`**

`bundles/io.github.eduardo.dbeaver.aichat/src/io/github/eduardo/dbeaver/aichat/Activator.java`:

```java
package io.github.eduardo.dbeaver.aichat;

import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

public class Activator extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "io.github.eduardo.dbeaver.aichat";

    private static Activator plugin;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        plugin = null;
        super.stop(context);
    }

    public static Activator getDefault() {
        return plugin;
    }
}
```

- [ ] **Step 8: Criar a feature**

`features/io.github.eduardo.dbeaver.aichat.feature/feature.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<feature
      id="io.github.eduardo.dbeaver.aichat.feature"
      label="DBeaver AI Chat"
      version="0.1.0.qualifier"
      provider-name="Eduardo">

   <description>
      Chat de IA dentro do DBeaver com acesso às conexões configuradas.
   </description>

   <requires>
      <import plugin="org.jkiss.dbeaver.core" version="24.1.0" match="greaterOrEqual"/>
   </requires>

   <plugin
         id="io.github.eduardo.dbeaver.aichat"
         version="0.0.0"
         unpack="false"/>
</feature>
```

`features/io.github.eduardo.dbeaver.aichat.feature/build.properties`:

```properties
bin.includes = feature.xml
```

`features/io.github.eduardo.dbeaver.aichat.feature/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>io.github.eduardo.dbeaver.aichat</groupId>
    <artifactId>aichat-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <relativePath>../../pom.xml</relativePath>
  </parent>
  <artifactId>io.github.eduardo.dbeaver.aichat.feature</artifactId>
  <packaging>eclipse-feature</packaging>
</project>
```

- [ ] **Step 9: Criar o update site**

`releng/update-site/category.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<site>
   <feature id="io.github.eduardo.dbeaver.aichat.feature" version="0.0.0">
      <category name="aichat"/>
   </feature>
   <category-def name="aichat" label="DBeaver AI Chat"/>
</site>
```

`releng/update-site/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>io.github.eduardo.dbeaver.aichat</groupId>
    <artifactId>aichat-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <relativePath>../../pom.xml</relativePath>
  </parent>
  <artifactId>io.github.eduardo.dbeaver.aichat.update-site</artifactId>
  <packaging>eclipse-repository</packaging>
</project>
```

- [ ] **Step 10: Build completo**

```bash
. scripts/env.sh
mvn clean verify 2>&1 | tail -30
unzip -l bundles/io.github.eduardo.dbeaver.aichat/target/io.github.eduardo.dbeaver.aichat-0.1.0-SNAPSHOT.jar | grep -E 'lib/|Activator'
ls releng/update-site/target/repository
```

Expected: `BUILD SUCCESS`; o jar lista `lib/aichat-core.jar`, `lib/commonmark.jar`, `lib/commonmark-ext-gfm-tables.jar` e `Activator.class`; o repositório tem `artifacts.jar`, `content.jar`, `features/` e `plugins/`.
Se o Tycho reclamar que não encontra `lib/*.jar` no Bundle-ClassPath durante a resolução, mova a execução `embed-libs` para a fase `validate`. Se a location `Directory` não for aceita, troque `type="Directory"` por `type="Profile"` com o mesmo `path` sem o sufixo `/plugins`.

- [ ] **Step 11: Commit**

```bash
git add .mvn pom.xml core/pom.xml bundles features releng
git commit -m "build: Tycho reactor with plain core module embedded in bundle" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

> Nas Tasks 2–9, `C/` = `core/src/main/java/io/github/eduardo/dbeaver/aichat/core/` e `T/` = `core/src/test/java/io/github/eduardo/dbeaver/aichat/core/`. Rodar testes do core: `. scripts/env.sh && mvn -q -f core/pom.xml test -Dtest=<Classe>`.

### Task 2: Base do core — JSON, cancelamento, modelo de mensagens, parser SSE

**Files:**
- Create: `C/Json.java`, `C/CancellationToken.java`, `C/CancelledException.java`
- Create: `C/llm/{Role,ToolCall,ToolSpec,ChatMessage,ChatRequest,AssistantTurn,StopReason,ProviderType,ProviderConfig,LlmProvider,LlmException,StreamErrorException,SseParser}.java`
- Test: `T/CancellationTokenTest.java`, `T/llm/SseParserTest.java`, `T/llm/ChatMessageTest.java`

**Interfaces:**
- Produces:
  - `Json.GSON`, `Json.str(JsonObject,String)`, `Json.text(JsonObject,String)`, `Json.obj(...)`, `Json.arr(...)`, `Json.parseObject(String)`
  - `CancellationToken{isCancelled(); cancel(); throwIfCancelled(); Registration onCancel(Runnable)}`, `CancellationToken.Registration extends AutoCloseable { void close(); }`, `CancelledException extends RuntimeException`
  - `enum Role {USER, ASSISTANT, TOOL}`; `record ToolCall(String id, String name, String argumentsJson)`; `record ToolSpec(String name, String description, JsonObject inputSchema)`
  - `record ChatMessage(Role role, String text, List<ToolCall> toolCalls, String toolCallId, boolean error, JsonArray rawContent, String rawFormat)` com fábricas `user(String)`, `assistant(AssistantTurn)`, `toolResult(String id, String content, boolean error)`
  - `record ChatRequest(String model, String systemPrompt, List<ChatMessage> messages, List<ToolSpec> tools, int maxTokens, Double temperature)`
  - `enum StopReason {END_TURN, TOOL_USE, MAX_TOKENS, REFUSAL, OTHER}`; `record AssistantTurn(String text, List<ToolCall> toolCalls, StopReason stopReason, JsonArray rawContent, String rawFormat)` + `isEmpty()`
  - `enum ProviderType {OPENAI_COMPATIBLE, ANTHROPIC}`; `record ProviderConfig(String id, String name, ProviderType type, String baseUrl, String model, int maxTokens, Double temperature, boolean serverFallback)`
  - `interface LlmProvider { AssistantTurn stream(ChatRequest, Consumer<String> onTextDelta, CancellationToken) throws LlmException; }`
  - `LlmException(String message, int status)` + `status()`; `StreamErrorException extends IOException`
  - `SseParser.parse(InputStream, SseParser.Handler, CancellationToken)`; `interface Handler { void onEvent(String event, String data) throws IOException; }`

- [ ] **Step 1: Escrever os testes**

`T/CancellationTokenTest.java`:

```java
package io.github.eduardo.dbeaver.aichat.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CancellationTokenTest {

    @Test
    void runsCallbacksExactlyOnce() {
        CancellationToken token = new CancellationToken();
        AtomicInteger calls = new AtomicInteger();
        token.onCancel(calls::incrementAndGet);
        token.cancel();
        token.cancel();
        assertTrue(token.isCancelled());
        assertEquals(1, calls.get());
    }

    @Test
    void callbackRegisteredAfterCancelRunsImmediately() {
        CancellationToken token = new CancellationToken();
        token.cancel();
        AtomicInteger calls = new AtomicInteger();
        token.onCancel(calls::incrementAndGet);
        assertEquals(1, calls.get());
    }

    @Test
    void closedRegistrationIsNotCalled() {
        CancellationToken token = new CancellationToken();
        AtomicInteger calls = new AtomicInteger();
        CancellationToken.Registration registration = token.onCancel(calls::incrementAndGet);
        registration.close();
        token.cancel();
        assertEquals(0, calls.get());
    }

    @Test
    void throwIfCancelled() {
        CancellationToken token = new CancellationToken();
        assertDoesNotThrow(token::throwIfCancelled);
        token.cancel();
        assertThrows(CancelledException.class, token::throwIfCancelled);
    }
}
```

`T/llm/SseParserTest.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.llm;

import static org.junit.jupiter.api.Assertions.*;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.CancelledException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SseParserTest {

    private static InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesEventsSeparatedByBlankLines() throws Exception {
        List<String> out = new ArrayList<>();
        SseParser.parse(stream("event: a\ndata: {\"x\":1}\n\ndata: second\n\n"),
                (event, data) -> out.add(event + "|" + data), new CancellationToken());
        assertEquals(List.of("a|{\"x\":1}", "null|second"), out);
    }

    @Test
    void joinsMultiLineDataAndIgnoresComments() throws Exception {
        List<String> out = new ArrayList<>();
        SseParser.parse(stream(": keep-alive\ndata:one\ndata: two\n\n"),
                (event, data) -> out.add(data), new CancellationToken());
        assertEquals(List.of("one\ntwo"), out);
    }

    @Test
    void dispatchesLastEventWithoutTrailingBlankLine() throws Exception {
        List<String> out = new ArrayList<>();
        SseParser.parse(stream("data: last"), (event, data) -> out.add(data), new CancellationToken());
        assertEquals(List.of("last"), out);
    }

    @Test
    void handlesCrLf() throws Exception {
        List<String> out = new ArrayList<>();
        SseParser.parse(stream("data: a\r\n\r\ndata: b\r\n\r\n"), (event, data) -> out.add(data), new CancellationToken());
        assertEquals(List.of("a", "b"), out);
    }

    @Test
    void stopsWhenCancelled() {
        CancellationToken token = new CancellationToken();
        token.cancel();
        assertThrows(CancelledException.class,
                () -> SseParser.parse(stream("data: a\n\n"), (event, data) -> fail("must not dispatch"), token));
    }
}
```

`T/llm/ChatMessageTest.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.llm;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChatMessageTest {

    @Test
    void nullTextAndToolCallsBecomeEmpty() {
        ChatMessage m = new ChatMessage(Role.ASSISTANT, null, null, null, false, null, null);
        assertEquals("", m.text());
        assertEquals(List.of(), m.toolCalls());
    }

    @Test
    void assistantFactoryCopiesTurn() {
        ToolCall call = new ToolCall("c1", "run_query", "{}");
        AssistantTurn turn = new AssistantTurn("oi", List.of(call), StopReason.TOOL_USE, null, null);
        ChatMessage m = ChatMessage.assistant(turn);
        assertEquals(Role.ASSISTANT, m.role());
        assertEquals("oi", m.text());
        assertEquals(List.of(call), m.toolCalls());
    }

    @Test
    void emptyTurnDetection() {
        assertTrue(new AssistantTurn("", List.of(), StopReason.END_TURN, null, null).isEmpty());
        assertFalse(new AssistantTurn("x", List.of(), StopReason.END_TURN, null, null).isEmpty());
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `mvn -q -f core/pom.xml test`
Expected: FAIL de compilação (`cannot find symbol CancellationToken`, `SseParser`, ...).

- [ ] **Step 3: Implementar**

`C/Json.java`:

```java
package io.github.eduardo.dbeaver.aichat.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Shared Gson instance and null-safe accessors. */
public final class Json {

    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {
    }

    /** String value of a primitive member, or null when missing/null. */
    public static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    /** Like {@link #str} but serializes objects/arrays instead of returning null. */
    public static String text(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    public static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public static JsonArray arr(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    public static JsonObject parseObject(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
```

`C/CancelledException.java`:

```java
package io.github.eduardo.dbeaver.aichat.core;

public class CancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CancelledException() {
        super("Cancelado");
    }
}
```

`C/CancellationToken.java`:

```java
package io.github.eduardo.dbeaver.aichat.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative cancellation shared by the HTTP stream, SQL execution and approval waits. */
public final class CancellationToken {

    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            for (Runnable callback : callbacks) {
                if (callbacks.remove(callback)) {
                    runQuietly(callback);
                }
            }
        }
    }

    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new CancelledException();
        }
    }

    /** Runs the callback on cancel (immediately if already cancelled). */
    public Registration onCancel(Runnable callback) {
        callbacks.add(callback);
        if (cancelled.get() && callbacks.remove(callback)) {
            runQuietly(callback);
        }
        return () -> callbacks.remove(callback);
    }

    private static void runQuietly(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ignored) {
            // cancellation callbacks are best effort
        }
    }
}
```

`C/llm/Role.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.llm;

public enum Role {
    USER, ASSISTANT, TOOL
}
```

`C/llm/ToolCall.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.llm;

public record ToolCall(String id, String name, String argumentsJson) {
}
```

`C/llm/ToolSpec.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.llm;

import com.google.gson.JsonObject;

public record ToolSpec(String name, String description, JsonObject inputSchema) {
}
```

`C/llm/StopReason.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.llm;

public enum StopReason {
    END_TURN, TOOL_USE, MAX_TOKENS, REFUSAL, OTHER
}
```

`C/llm/AssistantTurn.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.llm;

import com.google.gson.JsonArray;
import java.util.List;

/**
 * One complete model response. rawContent/rawFormat keep the provider-native content
 * (e.g. Anthropic thinking blocks with signatures) so it can be echoed back unchanged.
 */
public record AssistantTurn(String text, List<ToolCall> toolCalls, StopReason stopReason,
        JsonArray rawContent, String rawFormat) {

    public AssistantTurn {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        stopReason = stopReason == null ? StopReason.OTHER : stopReason;
    }

    public boolean isEmpty() {
        return text.isEmpty() && toolCalls.isEmpty() && (rawContent == null || rawContent.isEmpty());
    }
}
```

`C/llm/ChatMessage.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.llm;

import com.google.gson.JsonArray;
import java.util.List;

public record ChatMessage(Role role, String text, List<ToolCall> toolCalls, String toolCallId,
        boolean error, JsonArray rawContent, String rawFormat) {

    public ChatMessage {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static ChatMessage user(String text) {
        return new ChatMessage(Role.USER, text, List.of(), null, false, null, null);
    }

    public static ChatMessage assistant(AssistantTurn turn) {
        return new ChatMessage(Role.ASSISTANT, turn.text(), turn.toolCalls(), null, false,
                turn.rawContent(), turn.rawFormat());
    }

    public static ChatMessage toolResult(String toolCallId, String content, boolean error) {
        return new ChatMessage(Role.TOOL, content, List.of(), toolCallId, error, null, null);
    }
}
```

`C/llm/ChatRequest.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.llm;

import java.util.List;

/** maxTokens <= 0 means provider default; temperature null means provider default. */
public record ChatRequest(String model, String systemPrompt, List<ChatMessage> messages,
        List<ToolSpec> tools, int maxTokens, Double temperature) {

    public ChatRequest {
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
```

`C/llm/ProviderType.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.llm;

public enum ProviderType {
    OPENAI_COMPATIBLE, ANTHROPIC
}
```

`C/llm/ProviderConfig.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.llm;

/** User-configured provider. The API key is stored separately (secure storage). */
public record ProviderConfig(String id, String name, ProviderType type, String baseUrl, String model,
        int maxTokens, Double temperature, boolean serverFallback) {
}
```

`C/llm/LlmProvider.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.llm;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import java.util.function.Consumer;

public interface LlmProvider {

    /**
     * Streams one model response. Text deltas go to onTextDelta as they arrive.
     *
     * @throws io.github.eduardo.dbeaver.aichat.core.CancelledException when the token is cancelled
     */
    AssistantTurn stream(ChatRequest request, Consumer<String> onTextDelta, CancellationToken cancel)
            throws LlmException;
}
```

`C/llm/LlmException.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.llm;

public class LlmException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int status;

    /** status is the HTTP status code, or 0 for network/stream errors. */
    public LlmException(String message, int status) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
```

`C/llm/StreamErrorException.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.llm;

import java.io.IOException;

/** An error reported inside an otherwise successful SSE stream. */
public class StreamErrorException extends IOException {

    private static final long serialVersionUID = 1L;

    public StreamErrorException(String message) {
        super(message);
    }
}
```

`C/llm/SseParser.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.llm;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Minimal text/event-stream parser (event + data fields). */
public final class SseParser {

    public interface Handler {
        void onEvent(String event, String data) throws IOException;
    }

    private SseParser() {
    }

    public static void parse(InputStream in, Handler handler, CancellationToken cancel) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String event = null;
        StringBuilder data = new StringBuilder();
        boolean hasData = false;
        String line;
        while ((line = reader.readLine()) != null) {
            cancel.throwIfCancelled();
            if (line.isEmpty()) {
                if (hasData) {
                    handler.onEvent(event, data.toString());
                }
                event = null;
                data.setLength(0);
                hasData = false;
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            if ("event".equals(field)) {
                event = value;
            } else if ("data".equals(field)) {
                if (hasData) {
                    data.append('\n');
                }
                data.append(value);
                hasData = true;
            }
        }
        cancel.throwIfCancelled();
        if (hasData) {
            handler.onEvent(event, data.toString());
        }
    }
}
```

- [ ] **Step 4: Rodar e ver passar**

Run: `mvn -q -f core/pom.xml test -Dtest='CancellationTokenTest,SseParserTest,ChatMessageTest'`
Expected: `Tests run: 12, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add core/src
git commit -m "feat(core): message model, cancellation token and SSE parser" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Provedor OpenAI-compatible (streaming + tool calls)

**Files:**
- Create: `C/llm/HttpStreaming.java`, `C/llm/OpenAiMapper.java`, `C/llm/OpenAiStreamAccumulator.java`, `C/llm/OpenAiCompatibleProvider.java`
- Test: `T/llm/FakeHttpServer.java`, `T/llm/OpenAiCompatibleProviderTest.java`

**Interfaces:**
- Consumes: tipos da Task 2.
- Produces:
  - `HttpStreaming.defaultClient()` → `HttpClient` (HTTP/1.1, connect timeout 20 s, proxy do sistema)
  - `HttpStreaming.endpoint(String baseUrl, String path)` → `URI`
  - `HttpStreaming.post(HttpClient, URI, Map<String,String> headers, String body, CancellationToken)` → `InputStream` (lança `LlmException` com status em resposta não-2xx)
  - `HttpStreaming.closeQuietly(InputStream)`
  - `new OpenAiCompatibleProvider(ProviderConfig, String apiKey, HttpClient)` implements `LlmProvider`
  - Test helper `FakeHttpServer` (usado também na Task 4): `enqueue(int status, String body)`, `baseUrl()`, `lastPath`, `lastHeaders`, `bodies`, `lastJson()`

- [ ] **Step 1: Escrever o servidor HTTP falso**

`T/llm/FakeHttpServer.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/** Local HTTP server that replays queued responses and records requests. */
final class FakeHttpServer implements AutoCloseable {

    private record Response(int status, String body) {
    }

    private final HttpServer server;
    private final Queue<Response> queue = new ConcurrentLinkedQueue<>();
    final List<String> bodies = new CopyOnWriteArrayList<>();
    volatile String lastPath;
    volatile Headers lastHeaders;

    FakeHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastPath = exchange.getRequestURI().getPath();
            lastHeaders = exchange.getRequestHeaders();
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Response r = queue.poll();
            if (r == null) {
                r = new Response(500, "{\"error\":{\"message\":\"no response queued\"}}");
            }
            byte[] out = r.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", r.status() == 200 ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(r.status(), out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    void enqueue(int status, String body) {
        queue.add(new Response(status, body));
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    JsonObject lastJson() {
        return JsonParser.parseString(bodies.get(bodies.size() - 1)).getAsJsonObject();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
```

- [ ] **Step 2: Escrever os testes do provedor**

`T/llm/OpenAiCompatibleProviderTest.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.Json;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleProviderTest {

    private static ProviderConfig config(String baseUrl) {
        return new ProviderConfig("p1", "Teste", ProviderType.OPENAI_COMPATIBLE, baseUrl, "gpt-test", 0, null, false);
    }

    private static ChatRequest simpleRequest() {
        return new ChatRequest("gpt-test", "Você é um assistente.", List.of(ChatMessage.user("Oi")), List.of(), 0, null);
    }

    @Test
    void streamsTextAndSendsBearerToken() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(200, """
                    data: {"choices":[{"delta":{"role":"assistant","content":"Olá"}}]}

                    data: {"choices":[{"delta":{"content":" mundo"},"finish_reason":"stop"}]}

                    data: [DONE]

                    """);
            OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
                    config(server.baseUrl() + "/v1/"), "sk-test", HttpStreaming.defaultClient());
            List<String> deltas = new ArrayList<>();
            AssistantTurn turn = provider.stream(simpleRequest(), deltas::add, new CancellationToken());

            assertEquals(List.of("Olá", " mundo"), deltas);
            assertEquals("Olá mundo", turn.text());
            assertEquals(StopReason.END_TURN, turn.stopReason());
            assertEquals("/v1/chat/completions", server.lastPath);
            assertEquals("Bearer sk-test", server.lastHeaders.getFirst("Authorization"));
        }
    }

    @Test
    void omitsAuthorizationWhenKeyIsBlank() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(200, "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n");
            new OpenAiCompatibleProvider(config(server.baseUrl()), "", HttpStreaming.defaultClient())
                    .stream(simpleRequest(), t -> { }, new CancellationToken());
            assertNull(server.lastHeaders.getFirst("Authorization"));
        }
    }

    @Test
    void assemblesToolCallsAcrossDeltas() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(200, """
                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"run_query","arguments":""}}]}}]}

                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"sql\\":"}}]}}]}

                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"select 1\\"}"}}]}}]}

                    data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}

                    data: [DONE]

                    """);
            AssistantTurn turn = new OpenAiCompatibleProvider(config(server.baseUrl()), "k", HttpStreaming.defaultClient())
                    .stream(simpleRequest(), t -> { }, new CancellationToken());

            assertEquals(StopReason.TOOL_USE, turn.stopReason());
            assertEquals(1, turn.toolCalls().size());
            ToolCall call = turn.toolCalls().get(0);
            assertEquals("call_1", call.id());
            assertEquals("run_query", call.name());
            assertEquals("select 1", JsonParser.parseString(call.argumentsJson()).getAsJsonObject().get("sql").getAsString());
        }
    }

    @Test
    void stopWithToolCallsIsTreatedAsToolUse() throws Exception {
        // Some local servers (e.g. Ollama) report finish_reason "stop" together with tool calls.
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(200, """
                    data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"list_connections","arguments":"{}"}}]},"finish_reason":"stop"}]}

                    """);
            AssistantTurn turn = new OpenAiCompatibleProvider(config(server.baseUrl()), "k", HttpStreaming.defaultClient())
                    .stream(simpleRequest(), t -> { }, new CancellationToken());
            assertEquals(StopReason.TOOL_USE, turn.stopReason());
            assertEquals("call_0", turn.toolCalls().get(0).id());
        }
    }

    @Test
    void mapsConversationToOpenAiFormat() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(200, "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\n");
            ToolCall call = new ToolCall("call_1", "list_tables", "{\"schema\":\"public\"}");
            JsonArray anthropicRaw = new JsonArray();
            anthropicRaw.add(Json.parseObject("{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"s\"}"));
            List<ChatMessage> messages = List.of(
                    ChatMessage.user("Quais tabelas?"),
                    ChatMessage.assistant(new AssistantTurn("", List.of(call), StopReason.TOOL_USE, null, null)),
                    ChatMessage.toolResult("call_1", "- orders [TABLE]", false),
                    new ChatMessage(Role.ASSISTANT, "Resposta anterior", List.of(), null, false, anthropicRaw, "anthropic"),
                    ChatMessage.user("E agora?"));
            ToolSpec spec = new ToolSpec("list_tables", "Lista tabelas", Json.parseObject("{\"type\":\"object\",\"properties\":{}}"));
            ChatRequest request = new ChatRequest("gpt-test", "sys", messages, List.of(spec), 0, 0.2);

            new OpenAiCompatibleProvider(config(server.baseUrl()), "k", HttpStreaming.defaultClient())
                    .stream(request, t -> { }, new CancellationToken());

            JsonObject body = server.lastJson();
            assertTrue(body.get("stream").getAsBoolean());
            assertFalse(body.has("max_tokens"));
            assertEquals(0.2, body.get("temperature").getAsDouble());
            JsonArray sent = body.getAsJsonArray("messages");
            assertEquals("system", sent.get(0).getAsJsonObject().get("role").getAsString());
            JsonObject assistant = sent.get(2).getAsJsonObject();
            assertTrue(assistant.get("content").isJsonNull());
            assertEquals("list_tables", assistant.getAsJsonArray("tool_calls").get(0).getAsJsonObject()
                    .getAsJsonObject("function").get("name").getAsString());
            JsonObject toolMsg = sent.get(3).getAsJsonObject();
            assertEquals("tool", toolMsg.get("role").getAsString());
            assertEquals("call_1", toolMsg.get("tool_call_id").getAsString());
            // Anthropic raw content is ignored: plain text is sent.
            assertEquals("Resposta anterior", sent.get(4).getAsJsonObject().get("content").getAsString());
            JsonObject tool = body.getAsJsonArray("tools").get(0).getAsJsonObject();
            assertEquals("function", tool.get("type").getAsString());
            assertEquals("object", tool.getAsJsonObject("function").getAsJsonObject("parameters").get("type").getAsString());
        }
    }

    @Test
    void httpErrorBecomesLlmException() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(401, "{\"error\":{\"message\":\"Invalid API key\"}}");
            LlmException e = assertThrows(LlmException.class,
                    () -> new OpenAiCompatibleProvider(config(server.baseUrl()), "bad", HttpStreaming.defaultClient())
                            .stream(simpleRequest(), t -> { }, new CancellationToken()));
            assertEquals(401, e.status());
            assertTrue(e.getMessage().contains("Invalid API key"));
        }
    }

    @Test
    void errorObjectInsideStreamBecomesLlmException() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(200, "data: {\"error\":{\"message\":\"model not found\"}}\n\n");
            LlmException e = assertThrows(LlmException.class,
                    () -> new OpenAiCompatibleProvider(config(server.baseUrl()), "k", HttpStreaming.defaultClient())
                            .stream(simpleRequest(), t -> { }, new CancellationToken()));
            assertTrue(e.getMessage().contains("model not found"));
        }
    }
}
```

- [ ] **Step 3: Rodar e ver falhar**

Run: `mvn -q -f core/pom.xml test -Dtest=OpenAiCompatibleProviderTest`
Expected: FAIL de compilação (`OpenAiCompatibleProvider`, `HttpStreaming` não existem).

- [ ] **Step 4: Implementar**

`C/llm/HttpStreaming.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.llm;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.CancelledException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/** POST + streaming response body shared by all providers. */
public final class HttpStreaming {

    private HttpStreaming() {
    }

    public static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(ProxySelector.getDefault())
                .build();
    }

    public static URI endpoint(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    public static InputStream post(HttpClient http, URI uri, Map<String, String> headers, String body,
            CancellationToken cancel) throws LlmException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        CompletableFuture<HttpResponse<InputStream>> future =
                http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<InputStream> response;
        try (CancellationToken.Registration registration = cancel.onCancel(() -> future.cancel(true))) {
            response = future.get();
        } catch (CancellationException e) {
            throw new CancelledException();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException();
        } catch (ExecutionException e) {
            if (cancel.isCancelled()) {
                throw new CancelledException();
            }
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new LlmException("Falha ao conectar em " + uri + ": " + cause, 0);
        }
        if (response.statusCode() / 100 != 2) {
            String error;
            try (InputStream in = response.body()) {
                error = new String(in.readNBytes(4096), StandardCharsets.UTF_8);
            } catch (IOException e) {
                error = "";
            }
            throw new LlmException("HTTP " + response.statusCode() + " de " + uri + ": " + error, response.statusCode());
        }
        return response.body();
    }

    public static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // closing to abort a stream
        }
    }
}
```

`C/llm/OpenAiMapper.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/** Builds Chat Completions request bodies. Ignores provider-native raw content from other formats. */
final class OpenAiMapper {

    private OpenAiMapper() {
    }

    static JsonObject requestBody(ChatRequest request) {
        JsonObject body = new JsonObject();
        body.addProperty("model", request.model());
        body.addProperty("stream", true);
        JsonArray messages = new JsonArray();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(message("system", request.systemPrompt()));
        }
        for (ChatMessage m : request.messages()) {
            messages.add(toJson(m));
        }
        body.add("messages", messages);
        if (!request.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (ToolSpec spec : request.tools()) {
                JsonObject function = new JsonObject();
                function.addProperty("name", spec.name());
                function.addProperty("description", spec.description());
                function.add("parameters", spec.inputSchema());
                JsonObject tool = new JsonObject();
                tool.addProperty("type", "function");
                tool.add("function", function);
                tools.add(tool);
            }
            body.add("tools", tools);
        }
        if (request.maxTokens() > 0) {
            body.addProperty("max_tokens", request.maxTokens());
        }
        if (request.temperature() != null) {
            body.addProperty("temperature", request.temperature());
        }
        return body;
    }

    private static JsonObject toJson(ChatMessage m) {
        switch (m.role()) {
            case USER:
                return message("user", m.text());
            case TOOL: {
                JsonObject o = message("tool", m.text());
                o.addProperty("tool_call_id", m.toolCallId());
                return o;
            }
            default: {
                JsonObject o = new JsonObject();
                o.addProperty("role", "assistant");
                if (m.text().isEmpty()) {
                    o.add("content", JsonNull.INSTANCE);
                } else {
                    o.addProperty("content", m.text());
                }
                if (!m.toolCalls().isEmpty()) {
                    JsonArray calls = new JsonArray();
                    for (ToolCall call : m.toolCalls()) {
                        JsonObject function = new JsonObject();
                        function.addProperty("name", call.name());
                        function.addProperty("arguments", call.argumentsJson() == null ? "{}" : call.argumentsJson());
                        JsonObject c = new JsonObject();
                        c.addProperty("id", call.id());
                        c.addProperty("type", "function");
                        c.add("function", function);
                        calls.add(c);
                    }
                    o.add("tool_calls", calls);
                }
                return o;
            }
        }
    }

    private static JsonObject message(String role, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", content);
        return o;
    }
}
```

`C/llm/OpenAiStreamAccumulator.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.eduardo.dbeaver.aichat.core.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Folds Chat Completions stream chunks into one AssistantTurn. */
final class OpenAiStreamAccumulator {

    private static final class PartialCall {
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }

    private final Consumer<String> onText;
    private final StringBuilder text = new StringBuilder();
    private final TreeMap<Integer, PartialCall> calls = new TreeMap<>();
    private String finishReason;

    OpenAiStreamAccumulator(Consumer<String> onText) {
        this.onText = onText;
    }

    void accept(String data) throws StreamErrorException {
        if ("[DONE]".equals(data.trim())) {
            return;
        }
        JsonObject chunk;
        try {
            chunk = JsonParser.parseString(data).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new StreamErrorException("Resposta inválida do provedor: " + data);
        }
        JsonObject error = Json.obj(chunk, "error");
        if (error != null) {
            throw new StreamErrorException("Erro do provedor: " + Json.text(error, "message"));
        }
        JsonArray choices = Json.arr(chunk, "choices");
        if (choices == null || choices.isEmpty()) {
            return;
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        JsonObject delta = Json.obj(choice, "delta");
        if (delta != null) {
            String content = Json.str(delta, "content");
            if (content != null && !content.isEmpty()) {
                text.append(content);
                onText.accept(content);
            }
            JsonArray toolCalls = Json.arr(delta, "tool_calls");
            if (toolCalls != null) {
                for (JsonElement element : toolCalls) {
                    JsonObject tc = element.getAsJsonObject();
                    int index = tc.has("index") ? tc.get("index").getAsInt() : calls.size();
                    PartialCall partial = calls.computeIfAbsent(index, i -> new PartialCall());
                    String id = Json.str(tc, "id");
                    if (id != null) {
                        partial.id = id;
                    }
                    JsonObject function = Json.obj(tc, "function");
                    if (function != null) {
                        String name = Json.str(function, "name");
                        if (name != null) {
                            partial.name = name;
                        }
                        String args = Json.text(function, "arguments");
                        if (args != null) {
                            partial.arguments.append(args);
                        }
                    }
                }
            }
        }
        String reason = Json.str(choice, "finish_reason");
        if (reason != null) {
            finishReason = reason;
        }
    }

    AssistantTurn finish() {
        List<ToolCall> toolCalls = new ArrayList<>();
        calls.forEach((index, p) -> toolCalls.add(
                new ToolCall(p.id != null ? p.id : "call_" + index, p.name, p.arguments.toString())));
        StopReason stop;
        switch (finishReason == null ? "" : finishReason) {
            case "length":
                stop = StopReason.MAX_TOKENS;
                break;
            case "content_filter":
                stop = StopReason.REFUSAL;
                break;
            case "tool_calls":
            case "function_call":
                stop = StopReason.TOOL_USE;
                break;
            default:
                stop = StopReason.END_TURN;
        }
        if (stop == StopReason.END_TURN && !toolCalls.isEmpty()) {
            stop = StopReason.TOOL_USE;
        }
        return new AssistantTurn(text.toString(), toolCalls, stop, null, null);
    }
}
```

`C/llm/OpenAiCompatibleProvider.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.llm;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.CancelledException;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Any server implementing POST {baseUrl}/chat/completions with SSE (OpenAI, Ollama, LM Studio, OpenRouter...). */
public final class OpenAiCompatibleProvider implements LlmProvider {

    private final ProviderConfig config;
    private final String apiKey;
    private final HttpClient http;

    public OpenAiCompatibleProvider(ProviderConfig config, String apiKey, HttpClient http) {
        this.config = config;
        this.apiKey = apiKey;
        this.http = http;
    }

    @Override
    public AssistantTurn stream(ChatRequest request, Consumer<String> onTextDelta, CancellationToken cancel)
            throws LlmException {
        Map<String, String> headers = new LinkedHashMap<>();
        if (apiKey != null && !apiKey.isBlank()) {
            headers.put("Authorization", "Bearer " + apiKey.trim());
        }
        String body = OpenAiMapper.requestBody(request).toString();
        InputStream in = HttpStreaming.post(http, HttpStreaming.endpoint(config.baseUrl(), "/chat/completions"),
                headers, body, cancel);
        OpenAiStreamAccumulator accumulator = new OpenAiStreamAccumulator(onTextDelta);
        try (in; CancellationToken.Registration registration = cancel.onCancel(() -> HttpStreaming.closeQuietly(in))) {
            SseParser.parse(in, (event, data) -> accumulator.accept(data), cancel);
        } catch (StreamErrorException e) {
            throw new LlmException(e.getMessage(), 0);
        } catch (IOException e) {
            if (cancel.isCancelled()) {
                throw new CancelledException();
            }
            throw new LlmException("Conexão interrompida: " + e.getMessage(), 0);
        }
        return accumulator.finish();
    }
}
```

- [ ] **Step 5: Rodar e ver passar**

Run: `mvn -q -f core/pom.xml test -Dtest=OpenAiCompatibleProviderTest`
Expected: `Tests run: 7, Failures: 0, Errors: 0`.

- [ ] **Step 6: Commit**

```bash
git add core/src
git commit -m "feat(core): OpenAI-compatible streaming provider with tool calls" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Provedor Anthropic (Messages API, thinking, fallback) + ProviderFactory

**Files:**
- Create: `C/llm/AnthropicMapper.java`, `C/llm/AnthropicStreamAccumulator.java`, `C/llm/AnthropicProvider.java`, `C/llm/ProviderFactory.java`
- Test: `T/llm/AnthropicProviderTest.java`, `T/llm/AnthropicStreamAccumulatorTest.java`

**Interfaces:**
- Consumes: `HttpStreaming`, `SseParser`, `FakeHttpServer` (Task 3).
- Produces:
  - `new AnthropicProvider(ProviderConfig, String apiKey, HttpClient)`; constantes `AnthropicProvider.RAW_FORMAT = "anthropic"`, `AnthropicProvider.DEFAULT_MAX_TOKENS = 64000`, `AnthropicProvider.DEFAULT_BASE_URL = "https://api.anthropic.com"`
  - `AnthropicMapper.requestBody(ChatRequest, boolean serverFallback)`, `AnthropicMapper.echoContent(JsonArray raw)` (package-private)
  - `ProviderFactory.create(ProviderConfig, String apiKey, HttpClient)` → `LlmProvider`; `ProviderFactory.defaultHttpClient()`

- [ ] **Step 1: Escrever os testes**

`T/llm/AnthropicProviderTest.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.Json;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnthropicProviderTest {

    private static final String TEXT_STREAM = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_1","role":"assistant","content":[]}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Olá"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    private static ProviderConfig config(String baseUrl, boolean fallback) {
        return new ProviderConfig("a1", "Claude", ProviderType.ANTHROPIC, baseUrl, "claude-opus-5-5", 0, 0.7, fallback);
    }

    private static ChatRequest simpleRequest() {
        return new ChatRequest("claude-opus-5-5", "sys", List.of(ChatMessage.user("Oi")), List.of(), 0, 0.7);
    }

    @Test
    void streamsTextWithAnthropicHeaders() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(200, TEXT_STREAM);
            List<String> deltas = new ArrayList<>();
            AssistantTurn turn = new AnthropicProvider(config(server.baseUrl(), false), "sk-ant", HttpStreaming.defaultClient())
                    .stream(simpleRequest(), deltas::add, new CancellationToken());

            assertEquals(List.of("Olá"), deltas);
            assertEquals("Olá", turn.text());
            assertEquals(StopReason.END_TURN, turn.stopReason());
            assertEquals("/v1/messages", server.lastPath);
            assertEquals("sk-ant", server.lastHeaders.getFirst("x-api-key"));
            assertEquals("2023-06-01", server.lastHeaders.getFirst("anthropic-version"));
            assertNull(server.lastHeaders.getFirst("anthropic-beta"));
            assertFalse(server.lastJson().has("fallbacks"));
        }
    }

    @Test
    void keepsThinkingBlocksAndParsesToolInput() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(200, """
                    event: message_start
                    data: {"type":"message_start","message":{"id":"msg_1","role":"assistant","content":[]}}

                    event: content_block_start
                    data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig123"}}

                    event: content_block_stop
                    data: {"type":"content_block_stop","index":0}

                    event: content_block_start
                    data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Vou consultar."}}

                    event: content_block_stop
                    data: {"type":"content_block_stop","index":1}

                    event: content_block_start
                    data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_1","name":"run_query","input":{}}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\\"sql\\":"}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"\\"select 1\\"}"}}

                    event: content_block_stop
                    data: {"type":"content_block_stop","index":2}

                    event: message_delta
                    data: {"type":"message_delta","delta":{"stop_reason":"tool_use"}}

                    event: message_stop
                    data: {"type":"message_stop"}

                    """);
            AssistantTurn turn = new AnthropicProvider(config(server.baseUrl(), false), "k", HttpStreaming.defaultClient())
                    .stream(simpleRequest(), t -> { }, new CancellationToken());

            assertEquals(StopReason.TOOL_USE, turn.stopReason());
            assertEquals("Vou consultar.", turn.text());
            assertEquals(AnthropicProvider.RAW_FORMAT, turn.rawFormat());
            ToolCall call = turn.toolCalls().get(0);
            assertEquals("toolu_1", call.id());
            assertEquals("run_query", call.name());
            assertEquals("select 1", JsonParser.parseString(call.argumentsJson()).getAsJsonObject().get("sql").getAsString());
            JsonObject thinking = turn.rawContent().get(0).getAsJsonObject();
            assertEquals("thinking", thinking.get("type").getAsString());
            assertEquals("sig123", thinking.get("signature").getAsString());
            assertEquals("select 1", turn.rawContent().get(2).getAsJsonObject()
                    .getAsJsonObject("input").get("sql").getAsString());
        }
    }

    @Test
    void mapsHistoryToMessagesApiFormat() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(200, TEXT_STREAM);
            JsonArray raw = new JsonArray();
            raw.add(Json.parseObject("{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"s1\"}"));
            raw.add(Json.parseObject("{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"list_tables\",\"input\":{}}"));
            raw.add(Json.parseObject("{\"type\":\"tool_use\",\"id\":\"toolu_2\",\"name\":\"list_schemas\",\"input\":{}}"));
            List<ChatMessage> messages = List.of(
                    ChatMessage.user("Quais tabelas?"),
                    new ChatMessage(Role.ASSISTANT, "", List.of(new ToolCall("toolu_1", "list_tables", "{}"),
                            new ToolCall("toolu_2", "list_schemas", "{}")), null, false, raw, AnthropicProvider.RAW_FORMAT),
                    ChatMessage.toolResult("toolu_1", "- orders", false),
                    ChatMessage.toolResult("toolu_2", "falhou", true));
            ToolSpec spec = new ToolSpec("list_tables", "Lista tabelas", Json.parseObject("{\"type\":\"object\",\"properties\":{}}"));
            ChatRequest request = new ChatRequest("claude-opus-5-5", "sys", messages, List.of(spec), 0, 0.7);

            new AnthropicProvider(config(server.baseUrl(), true), "k", HttpStreaming.defaultClient())
                    .stream(request, t -> { }, new CancellationToken());

            JsonObject body = server.lastJson();
            assertEquals("sys", body.get("system").getAsString());
            assertEquals(AnthropicProvider.DEFAULT_MAX_TOKENS, body.get("max_tokens").getAsInt());
            assertFalse(body.has("temperature"));
            assertEquals("default", body.get("fallbacks").getAsString());
            assertEquals("server-side-fallback-2026-07-01", server.lastHeaders.getFirst("anthropic-beta"));
            JsonArray sent = body.getAsJsonArray("messages");
            assertEquals(3, sent.size());
            assertEquals(raw, sent.get(1).getAsJsonObject().getAsJsonArray("content"));
            JsonArray results = sent.get(2).getAsJsonObject().getAsJsonArray("content");
            assertEquals("user", sent.get(2).getAsJsonObject().get("role").getAsString());
            assertEquals(2, results.size());
            assertEquals("tool_result", results.get(0).getAsJsonObject().get("type").getAsString());
            assertEquals("toolu_2", results.get(1).getAsJsonObject().get("tool_use_id").getAsString());
            assertTrue(results.get(1).getAsJsonObject().get("is_error").getAsBoolean());
            assertTrue(body.getAsJsonArray("tools").get(0).getAsJsonObject().has("input_schema"));
        }
    }

    @Test
    void buildsContentFromTextAndToolCallsWhenNoRawContent() throws Exception {
        // History produced by an OpenAI-compatible provider, then sent to Anthropic.
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(200, TEXT_STREAM);
            List<ChatMessage> messages = List.of(
                    ChatMessage.user("Oi"),
                    ChatMessage.assistant(new AssistantTurn("Vou ver", List.of(new ToolCall("call_1", "list_connections", "{}")),
                            StopReason.TOOL_USE, null, null)),
                    ChatMessage.toolResult("call_1", "- prod", false),
                    ChatMessage.user("Continue"));
            new AnthropicProvider(config(server.baseUrl(), false), "k", HttpStreaming.defaultClient())
                    .stream(new ChatRequest("m", "", messages, List.of(), 1000, null), t -> { }, new CancellationToken());

            JsonObject body = server.lastJson();
            assertFalse(body.has("system"));
            assertEquals(1000, body.get("max_tokens").getAsInt());
            JsonArray sent = body.getAsJsonArray("messages");
            JsonArray assistant = sent.get(1).getAsJsonObject().getAsJsonArray("content");
            assertEquals("text", assistant.get(0).getAsJsonObject().get("type").getAsString());
            assertEquals("tool_use", assistant.get(1).getAsJsonObject().get("type").getAsString());
            // tool_result and the next user text are merged into one user message
            assertEquals(3, sent.size());
            JsonArray lastUser = sent.get(2).getAsJsonObject().getAsJsonArray("content");
            assertEquals("tool_result", lastUser.get(0).getAsJsonObject().get("type").getAsString());
            assertEquals("text", lastUser.get(1).getAsJsonObject().get("type").getAsString());
        }
    }

    @Test
    void retriesWithoutFallbackWhenRejected() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"fallbacks: not supported for this model\"}}");
            server.enqueue(200, TEXT_STREAM);
            AssistantTurn turn = new AnthropicProvider(config(server.baseUrl(), true), "k", HttpStreaming.defaultClient())
                    .stream(simpleRequest(), t -> { }, new CancellationToken());
            assertEquals("Olá", turn.text());
            assertEquals(2, server.bodies.size());
            assertFalse(server.lastJson().has("fallbacks"));
        }
    }

    @Test
    void errorEventBecomesLlmException() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(200, "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}\n\n");
            LlmException e = assertThrows(LlmException.class,
                    () -> new AnthropicProvider(config(server.baseUrl(), false), "k", HttpStreaming.defaultClient())
                            .stream(simpleRequest(), t -> { }, new CancellationToken()));
            assertTrue(e.getMessage().contains("Overloaded"));
        }
    }

    @Test
    void refusalStopReason() throws Exception {
        try (FakeHttpServer server = new FakeHttpServer()) {
            server.enqueue(200, TEXT_STREAM.replace("end_turn", "refusal"));
            AssistantTurn turn = new AnthropicProvider(config(server.baseUrl(), false), "k", HttpStreaming.defaultClient())
                    .stream(simpleRequest(), t -> { }, new CancellationToken());
            assertEquals(StopReason.REFUSAL, turn.stopReason());
        }
    }
}
```

`T/llm/AnthropicStreamAccumulatorTest.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import io.github.eduardo.dbeaver.aichat.core.Json;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnthropicStreamAccumulatorTest {

    private static AssistantTurn run(String... events) throws Exception {
        AnthropicStreamAccumulator acc = new AnthropicStreamAccumulator(t -> { });
        for (String e : events) {
            acc.accept(e);
        }
        return acc.finish();
    }

    @Test
    void onlyToolCallsAfterLastFallbackBlockAreReturned() throws Exception {
        AssistantTurn turn = run(
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_old\",\"name\":\"run_query\",\"input\":{}}}",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"fallback\"}}",
                "{\"type\":\"content_block_stop\",\"index\":1}",
                "{\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_new\",\"name\":\"run_query\",\"input\":{}}}",
                "{\"type\":\"content_block_stop\",\"index\":2}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"}}");
        assertEquals(1, turn.toolCalls().size());
        assertEquals("toolu_new", turn.toolCalls().get(0).id());
        assertEquals("{}", turn.toolCalls().get(0).argumentsJson());
    }

    @Test
    void invalidToolJsonIsReportedRawAndEchoedAsEmptyObject() throws Exception {
        AssistantTurn turn = run(
                "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"run_query\",\"input\":{}}}",
                "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"sql\\\": \\\"sel\"}}",
                "{\"type\":\"content_block_stop\",\"index\":0}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"}}");
        assertEquals(StopReason.MAX_TOKENS, turn.stopReason());
        assertEquals("{\"sql\": \"sel", turn.toolCalls().get(0).argumentsJson());
        assertEquals(0, turn.rawContent().get(0).getAsJsonObject().getAsJsonObject("input").size());
    }

    @Test
    void echoDropsThinkingAndToolUseBeforeFallback() {
        JsonArray raw = new JsonArray();
        raw.add(Json.parseObject("{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"s\"}"));
        raw.add(Json.parseObject("{\"type\":\"tool_use\",\"id\":\"old\",\"name\":\"x\",\"input\":{}}"));
        raw.add(Json.parseObject("{\"type\":\"text\",\"text\":\"parcial\"}"));
        raw.add(Json.parseObject("{\"type\":\"fallback\"}"));
        raw.add(Json.parseObject("{\"type\":\"text\",\"text\":\"final\"}"));
        raw.add(Json.parseObject("{\"type\":\"tool_use\",\"id\":\"new\",\"name\":\"x\",\"input\":{}}"));
        List<String> types = new ArrayList<>();
        for (JsonElement e : AnthropicMapper.echoContent(raw)) {
            types.add(e.getAsJsonObject().get("type").getAsString());
        }
        assertEquals(List.of("text", "fallback", "text", "tool_use"), types);
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `mvn -q -f core/pom.xml test -Dtest='Anthropic*Test'`
Expected: FAIL de compilação (`AnthropicProvider`, `AnthropicStreamAccumulator`, `AnthropicMapper` não existem).

- [ ] **Step 3: Implementar**

`C/llm/AnthropicMapper.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.eduardo.dbeaver.aichat.core.Json;
import java.util.List;
import java.util.Set;

/** Builds Messages API request bodies. */
final class AnthropicMapper {

    private static final Set<String> DROPPED_BEFORE_FALLBACK = Set.of("thinking", "redacted_thinking", "tool_use");

    private AnthropicMapper() {
    }

    static JsonObject requestBody(ChatRequest request, boolean serverFallback) {
        JsonObject body = new JsonObject();
        body.addProperty("model", request.model());
        body.addProperty("max_tokens", request.maxTokens() > 0 ? request.maxTokens() : AnthropicProvider.DEFAULT_MAX_TOKENS);
        body.addProperty("stream", true);
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            body.addProperty("system", request.systemPrompt());
        }
        body.add("messages", messages(request.messages()));
        if (!request.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (ToolSpec spec : request.tools()) {
                JsonObject tool = new JsonObject();
                tool.addProperty("name", spec.name());
                tool.addProperty("description", spec.description());
                tool.add("input_schema", spec.inputSchema());
                tools.add(tool);
            }
            body.add("tools", tools);
        }
        if (serverFallback) {
            body.addProperty("fallbacks", "default");
        }
        return body;
    }

    private static JsonArray messages(List<ChatMessage> history) {
        JsonArray out = new JsonArray();
        for (ChatMessage m : history) {
            switch (m.role()) {
                case TOOL: {
                    JsonObject result = new JsonObject();
                    result.addProperty("type", "tool_result");
                    result.addProperty("tool_use_id", m.toolCallId());
                    result.addProperty("content", m.text());
                    if (m.error()) {
                        result.addProperty("is_error", true);
                    }
                    appendUser(out, result);
                    break;
                }
                case USER: {
                    appendUser(out, textBlock(m.text()));
                    break;
                }
                default: {
                    JsonArray content = assistantContent(m);
                    if (!content.isEmpty()) {
                        JsonObject msg = new JsonObject();
                        msg.addProperty("role", "assistant");
                        msg.add("content", content);
                        out.add(msg);
                    }
                }
            }
        }
        return out;
    }

    /** Consecutive user-side blocks (tool results, text) go into a single user message. */
    private static void appendUser(JsonArray out, JsonObject block) {
        if (!out.isEmpty()) {
            JsonObject last = out.get(out.size() - 1).getAsJsonObject();
            if ("user".equals(Json.str(last, "role"))) {
                last.getAsJsonArray("content").add(block);
                return;
            }
        }
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        JsonArray content = new JsonArray();
        content.add(block);
        msg.add("content", content);
        out.add(msg);
    }

    private static JsonArray assistantContent(ChatMessage m) {
        if (AnthropicProvider.RAW_FORMAT.equals(m.rawFormat()) && m.rawContent() != null) {
            return echoContent(m.rawContent());
        }
        JsonArray content = new JsonArray();
        if (!m.text().isEmpty()) {
            content.add(textBlock(m.text()));
        }
        for (ToolCall call : m.toolCalls()) {
            JsonObject block = new JsonObject();
            block.addProperty("type", "tool_use");
            block.addProperty("id", call.id());
            block.addProperty("name", call.name());
            block.add("input", parseInputOrEmpty(call.argumentsJson()));
            content.add(block);
        }
        return content;
    }

    /**
     * Content blocks to send back unchanged, except that thinking/redacted_thinking/tool_use blocks
     * before the last server-side "fallback" block are omitted, as the API requires.
     */
    static JsonArray echoContent(JsonArray raw) {
        int lastFallback = -1;
        for (int i = 0; i < raw.size(); i++) {
            if ("fallback".equals(type(raw.get(i)))) {
                lastFallback = i;
            }
        }
        JsonArray out = new JsonArray();
        for (int i = 0; i < raw.size(); i++) {
            if (i < lastFallback && DROPPED_BEFORE_FALLBACK.contains(type(raw.get(i)))) {
                continue;
            }
            out.add(raw.get(i).deepCopy());
        }
        return out;
    }

    private static String type(JsonElement block) {
        return block.isJsonObject() ? Json.str(block.getAsJsonObject(), "type") : null;
    }

    private static JsonObject textBlock(String text) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        return block;
    }

    private static JsonObject parseInputOrEmpty(String json) {
        try {
            JsonElement e = JsonParser.parseString(json == null || json.isBlank() ? "{}" : json);
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }
}
```

`C/llm/AnthropicStreamAccumulator.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.eduardo.dbeaver.aichat.core.Json;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Rebuilds the assistant content blocks from Messages API stream events. */
final class AnthropicStreamAccumulator {

    private final Consumer<String> onText;
    private final TreeMap<Integer, JsonObject> blocks = new TreeMap<>();
    private final Map<Integer, StringBuilder> partialJson = new HashMap<>();
    private final Map<Integer, String> invalidArguments = new HashMap<>();
    private String stopReason;

    AnthropicStreamAccumulator(Consumer<String> onText) {
        this.onText = onText;
    }

    void accept(String data) throws StreamErrorException {
        JsonObject event;
        try {
            event = JsonParser.parseString(data).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new StreamErrorException("Resposta inválida da Anthropic: " + data);
        }
        String type = Json.str(event, "type");
        if (type == null) {
            return;
        }
        switch (type) {
            case "content_block_start": {
                int index = event.get("index").getAsInt();
                JsonObject block = Json.obj(event, "content_block");
                block = block == null ? new JsonObject() : block.deepCopy();
                if ("tool_use".equals(Json.str(block, "type"))) {
                    partialJson.put(index, new StringBuilder());
                }
                blocks.put(index, block);
                break;
            }
            case "content_block_delta":
                applyDelta(event.get("index").getAsInt(), Json.obj(event, "delta"));
                break;
            case "content_block_stop":
                finishBlock(event.get("index").getAsInt());
                break;
            case "message_delta": {
                String reason = Json.str(Json.obj(event, "delta"), "stop_reason");
                if (reason != null) {
                    stopReason = reason;
                }
                break;
            }
            case "error":
                throw new StreamErrorException("Erro da Anthropic: " + Json.text(Json.obj(event, "error"), "message"));
            default:
                // message_start, message_stop, ping
        }
    }

    private void applyDelta(int index, JsonObject delta) {
        JsonObject block = blocks.get(index);
        if (block == null || delta == null) {
            return;
        }
        String deltaType = String.valueOf(Json.str(delta, "type"));
        switch (deltaType) {
            case "text_delta": {
                String text = Json.str(delta, "text");
                if (text != null) {
                    append(block, "text", text);
                    onText.accept(text);
                }
                break;
            }
            case "thinking_delta":
                append(block, "thinking", Json.str(delta, "thinking"));
                break;
            case "signature_delta":
                block.addProperty("signature", Json.str(delta, "signature"));
                break;
            case "input_json_delta":
                partialJson.computeIfAbsent(index, i -> new StringBuilder()).append(Json.str(delta, "partial_json"));
                break;
            default:
                // unknown delta types are ignored; the block itself is kept as received
        }
    }

    private static void append(JsonObject block, String field, String value) {
        if (value == null) {
            return;
        }
        String current = Json.str(block, field);
        block.addProperty(field, current == null ? value : current + value);
    }

    private void finishBlock(int index) {
        StringBuilder json = partialJson.remove(index);
        JsonObject block = blocks.get(index);
        if (json == null || block == null) {
            return;
        }
        String raw = json.toString().isBlank() ? "{}" : json.toString();
        try {
            JsonElement input = JsonParser.parseString(raw);
            if (!input.isJsonObject()) {
                throw new IllegalStateException("tool input is not an object");
            }
            block.add("input", input);
        } catch (RuntimeException e) {
            block.add("input", new JsonObject());
            invalidArguments.put(index, raw);
        }
    }

    AssistantTurn finish() {
        int lastFallback = -1;
        for (Map.Entry<Integer, JsonObject> e : blocks.entrySet()) {
            if ("fallback".equals(Json.str(e.getValue(), "type"))) {
                lastFallback = e.getKey();
            }
        }
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        JsonArray raw = new JsonArray();
        for (Map.Entry<Integer, JsonObject> e : blocks.entrySet()) {
            JsonObject block = e.getValue();
            raw.add(block);
            String type = String.valueOf(Json.str(block, "type"));
            if ("text".equals(type)) {
                text.append(Json.str(block, "text") == null ? "" : Json.str(block, "text"));
            } else if ("tool_use".equals(type) && e.getKey() > lastFallback) {
                String args = invalidArguments.getOrDefault(e.getKey(),
                        block.has("input") ? block.get("input").toString() : "{}");
                calls.add(new ToolCall(Json.str(block, "id"), Json.str(block, "name"), args));
            }
        }
        StopReason stop;
        switch (stopReason == null ? "" : stopReason) {
            case "tool_use":
                stop = StopReason.TOOL_USE;
                break;
            case "max_tokens":
                stop = StopReason.MAX_TOKENS;
                break;
            case "refusal":
                stop = StopReason.REFUSAL;
                break;
            case "end_turn":
            case "stop_sequence":
                stop = StopReason.END_TURN;
                break;
            default:
                stop = StopReason.OTHER;
        }
        return new AssistantTurn(text.toString(), calls, stop, raw, AnthropicProvider.RAW_FORMAT);
    }
}
```

`C/llm/AnthropicProvider.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.llm;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.CancelledException;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Anthropic Messages API over raw HTTP + SSE. */
public final class AnthropicProvider implements LlmProvider {

    public static final String RAW_FORMAT = "anthropic";
    public static final int DEFAULT_MAX_TOKENS = 64000;
    public static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

    private static final String API_VERSION = "2023-06-01";
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final ProviderConfig config;
    private final String apiKey;
    private final HttpClient http;

    public AnthropicProvider(ProviderConfig config, String apiKey, HttpClient http) {
        this.config = config;
        this.apiKey = apiKey;
        this.http = http;
    }

    @Override
    public AssistantTurn stream(ChatRequest request, Consumer<String> onTextDelta, CancellationToken cancel)
            throws LlmException {
        try {
            return doStream(request, onTextDelta, cancel, config.serverFallback());
        } catch (LlmException e) {
            boolean fallbackRejected = e.status() == 400
                    && e.getMessage().toLowerCase(Locale.ROOT).contains("fallback");
            if (config.serverFallback() && fallbackRejected) {
                return doStream(request, onTextDelta, cancel, false);
            }
            throw e;
        }
    }

    private AssistantTurn doStream(ChatRequest request, Consumer<String> onTextDelta, CancellationToken cancel,
            boolean serverFallback) throws LlmException {
        Map<String, String> headers = new LinkedHashMap<>();
        if (apiKey != null && !apiKey.isBlank()) {
            headers.put("x-api-key", apiKey.trim());
        }
        headers.put("anthropic-version", API_VERSION);
        if (serverFallback) {
            headers.put("anthropic-beta", FALLBACK_BETA);
        }
        String baseUrl = config.baseUrl() == null || config.baseUrl().isBlank() ? DEFAULT_BASE_URL : config.baseUrl();
        String body = AnthropicMapper.requestBody(request, serverFallback).toString();
        InputStream in = HttpStreaming.post(http, HttpStreaming.endpoint(baseUrl, "/v1/messages"), headers, body, cancel);
        AnthropicStreamAccumulator accumulator = new AnthropicStreamAccumulator(onTextDelta);
        try (in; CancellationToken.Registration registration = cancel.onCancel(() -> HttpStreaming.closeQuietly(in))) {
            SseParser.parse(in, (event, data) -> accumulator.accept(data), cancel);
        } catch (StreamErrorException e) {
            throw new LlmException(e.getMessage(), 0);
        } catch (IOException e) {
            if (cancel.isCancelled()) {
                throw new CancelledException();
            }
            throw new LlmException("Conexão interrompida: " + e.getMessage(), 0);
        }
        return accumulator.finish();
    }
}
```

`C/llm/ProviderFactory.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.llm;

import java.net.http.HttpClient;

public final class ProviderFactory {

    private ProviderFactory() {
    }

    public static LlmProvider create(ProviderConfig config, String apiKey, HttpClient http) {
        switch (config.type()) {
            case ANTHROPIC:
                return new AnthropicProvider(config, apiKey, http);
            case OPENAI_COMPATIBLE:
            default:
                return new OpenAiCompatibleProvider(config, apiKey, http);
        }
    }

    public static HttpClient defaultHttpClient() {
        return HttpStreaming.defaultClient();
    }
}
```

- [ ] **Step 4: Rodar e ver passar**

Run: `mvn -q -f core/pom.xml test -Dtest='Anthropic*Test'`
Expected: `Tests run: 10, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add core/src
git commit -m "feat(core): Anthropic Messages provider with thinking echo and server fallback" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Classificador SQL leitura/escrita (fail-safe)

**Files:**
- Create: `C/sql/SqlClassifier.java`
- Test: `T/sql/SqlClassifierTest.java`

**Interfaces:**
- Produces: `SqlClassifier.SqlKind {READ, WRITE}`, `SqlClassifier.classify(String sql)` → `SqlKind`, `SqlClassifier.statementCount(String sql)` → `int` (−1 quando não dá para tokenizar).

Regras: um único comando; primeira palavra em `SELECT, WITH, SHOW, DESCRIBE, DESC, EXPLAIN, VALUES, TABLE`; nenhuma palavra `INSERT, UPDATE, DELETE, MERGE, INTO`; `EXPLAIN ... ANALYZE` é escrita; strings, identificadores entre aspas/colchetes/crases e strings `$tag$` são ignorados; `--` só é comentário quando seguido de espaço/fim de linha (regra do MySQL); `/*! ... */` (comentário executável do MySQL), comentário ou string sem fim, ou SQL vazio → escrita. Barra invertida não é tratada como escape (mais seguro nos dois sentidos).

- [ ] **Step 1: Escrever os testes**

`T/sql/SqlClassifierTest.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.sql;

import static org.junit.jupiter.api.Assertions.*;

import io.github.eduardo.dbeaver.aichat.core.sql.SqlClassifier.SqlKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqlClassifierTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "select 1",
            "SELECT * FROM t;",
            "  -- comment\nselect a from b",
            "/* x */ SELECT 1",
            "with x as (select 1) select * from x",
            "show tables",
            "explain select * from t",
            "describe t",
            "values (1)",
            "select 'delete from t' as s",
            "select \"update\" from t",
            "select [delete] from t",
            "select `insert` from t",
            "select 'it''s' from t",
            "select 1;  ;",
            "select $$ ; delete $$",
            "select $tag$ x; delete from t $tag$",
            "select a -- trailing comment ; delete\nfrom t",
            "SELECT * FROM orders WHERE status = 'shipped' ORDER BY created_at DESC LIMIT 10"
    })
    void readStatements(String sql) {
        assertEquals(SqlKind.READ, SqlClassifier.classify(sql), sql);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "delete from t",
            "update t set a = 1",
            "insert into t values (1)",
            "drop table t",
            "create table x (a int)",
            "alter table t add b int",
            "truncate t",
            "call proc()",
            "pragma foreign_keys = off",
            "grant select on t to u",
            "merge into t using s on 1 = 1 when matched then delete",
            "select 1; delete from t",
            "with d as (delete from t returning *) select * from d",
            "select * into new_t from t",
            "select * from t for update",
            "explain analyze delete from t",
            "explain analyze select 1",
            "select 1 /* unterminated",
            "select 'unterminated",
            "select 1 /*! ; delete from t */",
            "select 1--1; delete from t",
            "select 'a\\'; delete from t; --'"
    })
    void writeStatements(String sql) {
        assertEquals(SqlKind.WRITE, SqlClassifier.classify(sql), sql);
    }

    @Test
    void countsStatements() {
        assertEquals(1, SqlClassifier.statementCount("select 1;"));
        assertEquals(2, SqlClassifier.statementCount("delete from a; delete from b"));
        assertEquals(1, SqlClassifier.statementCount("insert into t values (';')"));
        assertEquals(-1, SqlClassifier.statementCount("select 'x"));
    }
}
```

O pom do core já traz `junit-jupiter` (inclui `junit-jupiter-params`).

- [ ] **Step 2: Rodar e ver falhar**

Run: `mvn -q -f core/pom.xml test -Dtest=SqlClassifierTest`
Expected: FAIL de compilação (`SqlClassifier` não existe).

- [ ] **Step 3: Implementar**

`C/sql/SqlClassifier.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Conservative read/write classification. Anything that cannot be proven to be a single
 * read-only statement is WRITE.
 */
public final class SqlClassifier {

    public enum SqlKind {
        READ, WRITE
    }

    private static final Set<String> READ_STARTERS =
            Set.of("SELECT", "WITH", "SHOW", "DESCRIBE", "DESC", "EXPLAIN", "VALUES", "TABLE");
    private static final Set<String> FORBIDDEN_IN_READ = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "INTO");

    private SqlClassifier() {
    }

    public static SqlKind classify(String sql) {
        List<List<String>> statements;
        try {
            statements = tokenize(sql == null ? "" : sql);
        } catch (IllegalArgumentException e) {
            return SqlKind.WRITE;
        }
        if (statements.size() != 1) {
            return SqlKind.WRITE;
        }
        List<String> words = statements.get(0);
        String first = words.get(0);
        if (!READ_STARTERS.contains(first)) {
            return SqlKind.WRITE;
        }
        if ("EXPLAIN".equals(first) && words.contains("ANALYZE")) {
            return SqlKind.WRITE;
        }
        for (String word : words) {
            if (FORBIDDEN_IN_READ.contains(word)) {
                return SqlKind.WRITE;
            }
        }
        return SqlKind.READ;
    }

    /** Number of non-empty statements, or -1 when the text cannot be tokenized. */
    public static int statementCount(String sql) {
        try {
            return tokenize(sql == null ? "" : sql).size();
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    /** Splits into statements, each a list of upper-cased bare words (keywords/identifiers). */
    static List<List<String>> tokenize(String sql) {
        List<List<String>> statements = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-' && (i + 2 >= n || Character.isWhitespace(sql.charAt(i + 2)))) {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? n : end + 1;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                if (i + 2 < n && sql.charAt(i + 2) == '!') {
                    throw new IllegalArgumentException("executable comment");
                }
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) {
                    throw new IllegalArgumentException("unterminated comment");
                }
                i = end + 2;
            } else if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(sql, i, c);
            } else if (c == '[') {
                int end = sql.indexOf(']', i + 1);
                if (end < 0) {
                    throw new IllegalArgumentException("unterminated bracket");
                }
                i = end + 1;
            } else if (c == '$' && dollarTag(sql, i) != null) {
                String tag = dollarTag(sql, i);
                int end = sql.indexOf(tag, i + tag.length());
                if (end < 0) {
                    throw new IllegalArgumentException("unterminated dollar quote");
                }
                i = end + tag.length();
            } else if (c == ';') {
                if (!current.isEmpty()) {
                    statements.add(current);
                    current = new ArrayList<>();
                }
                i++;
            } else if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_' || sql.charAt(i) == '$')) {
                    i++;
                }
                current.add(sql.substring(start, i).toUpperCase(Locale.ROOT));
            } else {
                i++;
            }
        }
        if (!current.isEmpty()) {
            statements.add(current);
        }
        return statements;
    }

    /** Returns the index after the closing quote; doubled quotes are escapes. Backslash is not. */
    private static int skipQuoted(String sql, int start, char quote) {
        int i = start + 1;
        while (i < sql.length()) {
            if (sql.charAt(i) == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        throw new IllegalArgumentException("unterminated quote");
    }

    /** "$$" or "$tag$" starting at i, or null (e.g. "$1" positional parameter). */
    private static String dollarTag(String sql, int i) {
        int j = i + 1;
        while (j < sql.length() && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
            j++;
        }
        if (j < sql.length() && sql.charAt(j) == '$') {
            String tag = sql.substring(i, j + 1);
            if (tag.length() > 2 && Character.isDigit(tag.charAt(1))) {
                return null;
            }
            return tag;
        }
        return null;
    }
}
```

- [ ] **Step 4: Rodar e ver passar**

Run: `mvn -q -f core/pom.xml test -Dtest=SqlClassifierTest`
Expected: `Tests run: 44, Failures: 0, Errors: 0` (19 leitura + 24 escrita + 1).

- [ ] **Step 5: Commit**

```bash
git add core/src
git commit -m "feat(core): fail-safe SQL read/write classifier" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Camada de ferramentas (gateway abstrato + 6 tools + aprovação)

**Files:**
- Create em `C/tools/`: `DatabaseGateway`, `GatewayException`, `ConnectionInfo`, `TableInfo`, `ColumnInfo`, `ForeignKeyInfo`, `IndexInfo`, `TableDescription`, `QueryResult`, `ToolException`, `ToolOutcome`, `ToolLimits`, `ToolContext`, `ApprovalGate`, `ApprovalRequest`, `Decision`, `DbTool`, `Args`, `ConnectionResolver`, `TextTable`, `ToolRegistry`, `ListConnectionsTool`, `ListSchemasTool`, `ListTablesTool`, `DescribeTableTool`, `RunQueryTool`, `ExecuteWriteTool`
- Test: `T/tools/FakeGateway.java`, `T/tools/ToolsTest.java`, `T/tools/TextTableTest.java`

**Interfaces:**
- Consumes: `ToolSpec`, `ToolCall` (Task 2), `CancellationToken`, `SqlClassifier` (Task 5).
- Produces:
  - `interface DatabaseGateway` com `listConnections()`, `listSchemas(id)`, `listTables(id, schema, filter, limit)`, `describeTable(id, schema, table)`, `runReadQuery(id, sql, maxRows, cancel)`, `executeUpdate(id, sql, cancel)`, `classifyNative(id, sql)` (assinaturas exatas abaixo)
  - records `ConnectionInfo(String id, String name, String driver, String project, boolean connected, boolean readOnly)`, `TableInfo(String name, String type)`, `ColumnInfo(String name, String type, boolean nullable, String defaultValue)`, `ForeignKeyInfo(String name, List<String> columns, String referencedTable, List<String> referencedColumns)`, `IndexInfo(String name, boolean unique, List<String> columns)`, `TableDescription(String schema, String name, String type, List<ColumnInfo> columns, List<String> primaryKey, List<ForeignKeyInfo> foreignKeys, List<IndexInfo> indexes)`, `QueryResult(List<String> columns, List<List<String>> rows, boolean truncated)`
  - `record ToolOutcome(String content, boolean error)` + `ok(String)`/`error(String)`
  - `record ToolLimits(int maxRows, int maxResultChars, int maxListItems)` + `ToolLimits.DEFAULT = (200, 16000, 500)`
  - `record ToolContext(String defaultConnectionId, ApprovalGate approvalGate, CancellationToken cancel, ToolLimits limits)`
  - `interface ApprovalGate { Decision requestApproval(ApprovalRequest request, CancellationToken cancel); }`, `enum Decision {APPROVED, REJECTED}`, `record ApprovalRequest(String connectionName, String sql, String reason)`
  - `ToolRegistry.standard(DatabaseGateway)`, `ToolRegistry.specs()` → `List<ToolSpec>`, `ToolRegistry.execute(ToolCall, ToolContext)` → `ToolOutcome` (nunca lança, exceto `CancelledException`)
  - `TextTable.format(QueryResult, int maxChars)`
  - Nomes das tools: `list_connections`, `list_schemas`, `list_tables`, `describe_table`, `run_query`, `execute_write`

- [ ] **Step 1: Escrever o gateway falso e os testes**

`T/tools/FakeGateway.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.sql.SqlClassifier.SqlKind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

final class FakeGateway implements DatabaseGateway {

    final List<ConnectionInfo> connections = new ArrayList<>(List.of(
            new ConnectionInfo("pg-1", "prod-pg", "PostgreSQL", "General", true, false),
            new ConnectionInfo("lite-1", "local-sqlite", "SQLite", "General", false, true)));
    List<String> schemas = List.of("public", "sales");
    List<TableInfo> tables = List.of(new TableInfo("customers", "TABLE"), new TableInfo("orders", "TABLE"),
            new TableInfo("v_totals", "VIEW"));
    TableDescription description = new TableDescription("public", "orders", "TABLE",
            List.of(new ColumnInfo("id", "integer", false, null),
                    new ColumnInfo("customer_id", "integer", true, null),
                    new ColumnInfo("created_at", "timestamp", false, "now()")),
            List.of("id"),
            List.of(new ForeignKeyInfo("fk_customer", List.of("customer_id"), "public.customers", List.of("id"))),
            List.of(new IndexInfo("idx_orders_customer", false, List.of("customer_id"))));
    QueryResult queryResult = new QueryResult(List.of("id", "name"),
            List.of(List.of("1", "Ana"), Arrays.asList("2", null)), false);
    Optional<SqlKind> nativeKind = Optional.empty();
    GatewayException failure;
    long updateCount = 3;

    final List<String> reads = new ArrayList<>();
    final List<String> writes = new ArrayList<>();
    String lastConnectionId;
    int lastMaxRows;
    int lastLimit;

    @Override
    public List<ConnectionInfo> listConnections() {
        return connections;
    }

    @Override
    public List<String> listSchemas(String connectionId) throws GatewayException {
        lastConnectionId = connectionId;
        if (failure != null) {
            throw failure;
        }
        return schemas;
    }

    @Override
    public List<TableInfo> listTables(String connectionId, String schema, String nameFilter, int limit) {
        lastConnectionId = connectionId;
        lastLimit = limit;
        return tables.subList(0, Math.min(limit, tables.size()));
    }

    @Override
    public TableDescription describeTable(String connectionId, String schema, String table) {
        lastConnectionId = connectionId;
        return description;
    }

    @Override
    public QueryResult runReadQuery(String connectionId, String sql, int maxRows, CancellationToken cancel) {
        lastConnectionId = connectionId;
        lastMaxRows = maxRows;
        reads.add(sql);
        return queryResult;
    }

    @Override
    public long executeUpdate(String connectionId, String sql, CancellationToken cancel) {
        lastConnectionId = connectionId;
        writes.add(sql);
        return updateCount;
    }

    @Override
    public Optional<SqlKind> classifyNative(String connectionId, String sql) {
        return nativeKind;
    }
}
```

`T/tools/ToolsTest.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

import static org.junit.jupiter.api.Assertions.*;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.llm.ToolCall;
import io.github.eduardo.dbeaver.aichat.core.sql.SqlClassifier.SqlKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolsTest {

    private FakeGateway gateway;
    private ToolRegistry registry;
    private final List<ApprovalRequest> approvals = new ArrayList<>();

    @BeforeEach
    void setUp() {
        gateway = new FakeGateway();
        registry = ToolRegistry.standard(gateway);
        approvals.clear();
    }

    private ToolContext ctx(String defaultConnection, Decision decision) {
        ApprovalGate gate = (request, cancel) -> {
            approvals.add(request);
            return decision;
        };
        return new ToolContext(defaultConnection, gate, new CancellationToken(), new ToolLimits(200, 16000, 2));
    }

    private ToolOutcome call(String tool, String args, ToolContext ctx) {
        return registry.execute(new ToolCall("c1", tool, args), ctx);
    }

    @Test
    void exposesSixTools() {
        List<String> names = registry.specs().stream().map(s -> s.name()).toList();
        assertEquals(List.of("list_connections", "list_schemas", "list_tables", "describe_table", "run_query",
                "execute_write"), names);
        registry.specs().forEach(s -> assertEquals("object", s.inputSchema().get("type").getAsString()));
    }

    @Test
    void listConnections() {
        ToolOutcome out = call("list_connections", "{}", ctx(null, Decision.APPROVED));
        assertFalse(out.error());
        assertTrue(out.content().contains("prod-pg (id: pg-1"), out.content());
        assertTrue(out.content().contains("somente leitura"), out.content());
    }

    @Test
    void resolvesConnectionByNameIgnoringCase() {
        ToolOutcome out = call("run_query", "{\"connection\":\"PROD-PG\",\"sql\":\"select 1\"}", ctx(null, Decision.APPROVED));
        assertFalse(out.error(), out.content());
        assertEquals("pg-1", gateway.lastConnectionId);
    }

    @Test
    void usesDefaultConnection() {
        call("list_schemas", "{}", ctx("pg-1", Decision.APPROVED));
        assertEquals("pg-1", gateway.lastConnectionId);
    }

    @Test
    void missingConnectionListsAvailableOnes() {
        ToolOutcome out = call("list_schemas", "{}", ctx(null, Decision.APPROVED));
        assertTrue(out.error());
        assertTrue(out.content().contains("prod-pg, local-sqlite"), out.content());
    }

    @Test
    void listTablesShowsTruncationNotice() {
        ToolOutcome out = call("list_tables", "{\"schema\":\"public\"}", ctx("pg-1", Decision.APPROVED));
        assertEquals(3, gateway.lastLimit);
        assertTrue(out.content().contains("customers [TABLE]"));
        assertTrue(out.content().contains("orders [TABLE]"));
        assertFalse(out.content().contains("v_totals"));
        assertTrue(out.content().contains("omitidos"));
    }

    @Test
    void describeTableFormatsKeys() {
        ToolOutcome out = call("describe_table", "{\"table\":\"orders\"}", ctx("pg-1", Decision.APPROVED));
        assertTrue(out.content().contains("created_at timestamp NOT NULL DEFAULT now()"), out.content());
        assertTrue(out.content().contains("Chave primária: id"));
        assertTrue(out.content().contains("fk_customer (customer_id) -> public.customers (id)"));
        assertTrue(out.content().contains("idx_orders_customer (customer_id)"));
    }

    @Test
    void runQueryRejectsWrites() {
        ToolOutcome out = call("run_query", "{\"sql\":\"delete from orders\"}", ctx("pg-1", Decision.APPROVED));
        assertTrue(out.error());
        assertTrue(out.content().contains("execute_write"));
        assertTrue(gateway.reads.isEmpty());
    }

    @Test
    void runQueryRejectsWhenNativeParserSaysWrite() {
        gateway.nativeKind = Optional.of(SqlKind.WRITE);
        ToolOutcome out = call("run_query", "{\"sql\":\"select f()\"}", ctx("pg-1", Decision.APPROVED));
        assertTrue(out.error());
        assertTrue(gateway.reads.isEmpty());
    }

    @Test
    void runQueryFormatsRowsAndCapsMaxRows() {
        ToolOutcome out = call("run_query", "{\"sql\":\"select id, name from c\",\"max_rows\":5000}", ctx("pg-1", Decision.APPROVED));
        assertFalse(out.error(), out.content());
        assertEquals(200, gateway.lastMaxRows);
        assertTrue(out.content().startsWith("2 linha(s)"), out.content());
        assertTrue(out.content().contains("2 | NULL"), out.content());
    }

    @Test
    void executeWriteRunsAfterApproval() {
        ToolOutcome out = call("execute_write", "{\"sql\":\"delete from orders where id = 3\",\"reason\":\"limpar teste\"}",
                ctx("pg-1", Decision.APPROVED));
        assertFalse(out.error(), out.content());
        assertEquals(List.of("delete from orders where id = 3"), gateway.writes);
        assertEquals("prod-pg", approvals.get(0).connectionName());
        assertEquals("limpar teste", approvals.get(0).reason());
        assertTrue(out.content().contains("Linhas afetadas: 3"));
    }

    @Test
    void executeWriteRejectedByUser() {
        ToolOutcome out = call("execute_write", "{\"sql\":\"delete from orders\",\"reason\":\"x\"}", ctx("pg-1", Decision.REJECTED));
        assertTrue(out.error());
        assertTrue(gateway.writes.isEmpty());
    }

    @Test
    void executeWriteBlockedOnReadOnlyConnection() {
        ToolOutcome out = call("execute_write", "{\"sql\":\"delete from t\",\"reason\":\"x\",\"connection\":\"lite-1\"}",
                ctx(null, Decision.APPROVED));
        assertTrue(out.error());
        assertTrue(approvals.isEmpty());
        assertTrue(gateway.writes.isEmpty());
    }

    @Test
    void executeWriteRefusesMultipleStatements() {
        ToolOutcome out = call("execute_write", "{\"sql\":\"delete from a; delete from b\",\"reason\":\"x\"}",
                ctx("pg-1", Decision.APPROVED));
        assertTrue(out.error());
        assertTrue(approvals.isEmpty());
    }

    @Test
    void invalidJsonArgumentsAreReported() {
        ToolOutcome out = call("run_query", "{\"sql\": \"sel", ctx("pg-1", Decision.APPROVED));
        assertTrue(out.error());
        assertTrue(out.content().contains("INVALID_JSON"));
    }

    @Test
    void unknownToolIsReported() {
        ToolOutcome out = call("drop_everything", "{}", ctx("pg-1", Decision.APPROVED));
        assertTrue(out.error());
        assertTrue(out.content().contains("drop_everything"));
    }

    @Test
    void gatewayFailureBecomesErrorOutcome() {
        gateway.failure = new GatewayException("Falha ao conectar em prod-pg: timeout", null);
        ToolOutcome out = call("list_schemas", "{}", ctx("pg-1", Decision.APPROVED));
        assertTrue(out.error());
        assertTrue(out.content().contains("timeout"));
    }

    @Test
    void missingRequiredParameter() {
        ToolOutcome out = call("describe_table", "{}", ctx("pg-1", Decision.APPROVED));
        assertTrue(out.error());
        assertTrue(out.content().contains("table"));
    }
}
```

`T/tools/TextTableTest.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextTableTest {

    @Test
    void escapesNewlinesAndPipesAndTruncatesLongCells() {
        QueryResult r = new QueryResult(List.of("a"), List.of(List.of("x|y\nz"), List.of("w".repeat(500))), false);
        String out = TextTable.format(r, 10_000);
        assertTrue(out.contains("x\\|y\\nz"), out);
        assertTrue(out.contains("w".repeat(200) + "…"), out);
        assertFalse(out.contains("w".repeat(201)), out);
    }

    @Test
    void stopsAtMaxChars() {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            rows.add(List.of("linha " + i));
        }
        String out = TextTable.format(new QueryResult(List.of("c"), rows, false), 500);
        assertTrue(out.length() < 650, "length " + out.length());
        assertTrue(out.contains("saída truncada"), out);
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `mvn -q -f core/pom.xml test -Dtest='ToolsTest,TextTableTest'`
Expected: FAIL de compilação (tipos de `tools` não existem).

- [ ] **Step 3: Implementar os tipos de dados**

`C/tools/DatabaseGateway.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.sql.SqlClassifier.SqlKind;
import java.util.List;
import java.util.Optional;

/** Database access as seen by the tools. Implemented on top of the DBeaver API in the bundle. */
public interface DatabaseGateway {

    List<ConnectionInfo> listConnections();

    /** Schema paths ("schema" or "catalog.schema"); empty when the database has no schemas. */
    List<String> listSchemas(String connectionId) throws GatewayException;

    /** At most {@code limit} entries. schema null = default schema; nameFilter null = all. */
    List<TableInfo> listTables(String connectionId, String schema, String nameFilter, int limit) throws GatewayException;

    TableDescription describeTable(String connectionId, String schema, String table) throws GatewayException;

    /** Runs an already-classified read statement, returning at most maxRows rows. */
    QueryResult runReadQuery(String connectionId, String sql, int maxRows, CancellationToken cancel) throws GatewayException;

    /** Runs an approved write statement and commits; returns affected rows (-1 if unknown). */
    long executeUpdate(String connectionId, String sql, CancellationToken cancel) throws GatewayException;

    /** Second opinion from the database's own SQL parser, when available. */
    Optional<SqlKind> classifyNative(String connectionId, String sql);
}
```

`C/tools/GatewayException.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

public class GatewayException extends Exception {

    private static final long serialVersionUID = 1L;

    public GatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`C/tools/ToolException.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

/** A problem with the tool call itself; the message is shown to the model. */
public class ToolException extends Exception {

    private static final long serialVersionUID = 1L;

    public ToolException(String message) {
        super(message);
    }
}
```

`C/tools/ConnectionInfo.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

public record ConnectionInfo(String id, String name, String driver, String project, boolean connected, boolean readOnly) {
}
```

`C/tools/TableInfo.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

public record TableInfo(String name, String type) {
}
```

`C/tools/ColumnInfo.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

public record ColumnInfo(String name, String type, boolean nullable, String defaultValue) {
}
```

`C/tools/ForeignKeyInfo.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

import java.util.List;

public record ForeignKeyInfo(String name, List<String> columns, String referencedTable, List<String> referencedColumns) {
}
```

`C/tools/IndexInfo.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

import java.util.List;

public record IndexInfo(String name, boolean unique, List<String> columns) {
}
```

`C/tools/TableDescription.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

import java.util.List;

public record TableDescription(String schema, String name, String type, List<ColumnInfo> columns,
        List<String> primaryKey, List<ForeignKeyInfo> foreignKeys, List<IndexInfo> indexes) {
}
```

`C/tools/QueryResult.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

import java.util.List;

/** Cell values already converted to text; null means SQL NULL. */
public record QueryResult(List<String> columns, List<List<String>> rows, boolean truncated) {
}
```

`C/tools/ToolOutcome.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

public record ToolOutcome(String content, boolean error) {

    public static ToolOutcome ok(String content) {
        return new ToolOutcome(content, false);
    }

    public static ToolOutcome error(String content) {
        return new ToolOutcome(content, true);
    }
}
```

`C/tools/ToolLimits.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

public record ToolLimits(int maxRows, int maxResultChars, int maxListItems) {

    public static final ToolLimits DEFAULT = new ToolLimits(200, 16000, 500);
}
```

`C/tools/Decision.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

public enum Decision {
    APPROVED, REJECTED
}
```

`C/tools/ApprovalRequest.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

public record ApprovalRequest(String connectionName, String sql, String reason) {
}
```

`C/tools/ApprovalGate.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;

public interface ApprovalGate {

    /**
     * Blocks until the user decides.
     *
     * @throws io.github.eduardo.dbeaver.aichat.core.CancelledException when cancelled while waiting
     */
    Decision requestApproval(ApprovalRequest request, CancellationToken cancel);
}
```

`C/tools/ToolContext.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;

public record ToolContext(String defaultConnectionId, ApprovalGate approvalGate, CancellationToken cancel,
        ToolLimits limits) {
}
```

- [ ] **Step 4: Implementar helpers e registry**

`C/tools/DbTool.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

import com.google.gson.JsonObject;
import io.github.eduardo.dbeaver.aichat.core.llm.ToolSpec;

public interface DbTool {

    String name();

    String description();

    JsonObject inputSchema();

    ToolOutcome execute(JsonObject args, ToolContext ctx) throws ToolException, GatewayException;

    default ToolSpec spec() {
        return new ToolSpec(name(), description(), inputSchema());
    }
}
```

`C/tools/Args.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

final class Args {

    private Args() {
    }

    static String optString(JsonObject args, String key) throws ToolException {
        JsonElement e = args.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonPrimitive()) {
            throw new ToolException("Parâmetro '" + key + "' deve ser texto.");
        }
        String value = e.getAsString();
        return value.isBlank() ? null : value;
    }

    static String requireString(JsonObject args, String key) throws ToolException {
        String value = optString(args, key);
        if (value == null) {
            throw new ToolException("Parâmetro obrigatório ausente: '" + key + "'.");
        }
        return value;
    }

    static Integer optInt(JsonObject args, String key) throws ToolException {
        JsonElement e = args.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        try {
            return e.getAsInt();
        } catch (RuntimeException ex) {
            throw new ToolException("Parâmetro '" + key + "' deve ser um número inteiro.");
        }
    }
}
```

`C/tools/ConnectionResolver.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

import java.util.List;
import java.util.stream.Collectors;

final class ConnectionResolver {

    private ConnectionResolver() {
    }

    /** Matches by exact id first, then by name ignoring case; falls back to the default connection. */
    static ConnectionInfo resolve(DatabaseGateway gateway, String requested, String defaultId) throws ToolException {
        List<ConnectionInfo> all = gateway.listConnections();
        String key = requested != null ? requested : defaultId;
        if (key == null) {
            throw new ToolException("Nenhuma conexão informada e nenhuma conexão padrão selecionada. "
                    + "Conexões disponíveis: " + names(all));
        }
        for (ConnectionInfo c : all) {
            if (c.id().equals(key)) {
                return c;
            }
        }
        for (ConnectionInfo c : all) {
            if (c.name().equalsIgnoreCase(key)) {
                return c;
            }
        }
        throw new ToolException("Conexão não encontrada: " + key + ". Conexões disponíveis: " + names(all));
    }

    private static String names(List<ConnectionInfo> all) {
        return all.isEmpty() ? "(nenhuma)" : all.stream().map(ConnectionInfo::name).collect(Collectors.joining(", "));
    }
}
```

`C/tools/TextTable.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

import java.util.List;

/** Compact pipe-separated rendering of query results for the model. */
public final class TextTable {

    static final int MAX_CELL = 200;

    private TextTable() {
    }

    public static String format(QueryResult result, int maxChars) {
        StringBuilder sb = new StringBuilder();
        StringBuilder header = new StringBuilder();
        for (int i = 0; i < result.columns().size(); i++) {
            if (i > 0) {
                header.append(" | ");
            }
            header.append(cell(result.columns().get(i)));
        }
        sb.append(header).append('\n');
        sb.append("-".repeat(Math.max(3, Math.min(80, header.length())))).append('\n');
        int shown = 0;
        for (List<String> row : result.rows()) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    line.append(" | ");
                }
                line.append(cell(row.get(i)));
            }
            if (sb.length() + line.length() + 1 > maxChars) {
                sb.append("... saída truncada em ").append(maxChars).append(" caracteres; ")
                        .append(result.rows().size() - shown).append(" linha(s) não exibida(s)\n");
                return sb.toString();
            }
            sb.append(line).append('\n');
            shown++;
        }
        return sb.toString();
    }

    static String cell(String value) {
        if (value == null) {
            return "NULL";
        }
        String s = value.replace("\r", "").replace("\n", "\\n").replace("|", "\\|");
        return s.length() > MAX_CELL ? s.substring(0, MAX_CELL) + "…" : s;
    }
}
```

`C/tools/ToolRegistry.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.eduardo.dbeaver.aichat.core.CancelledException;
import io.github.eduardo.dbeaver.aichat.core.llm.ToolCall;
import io.github.eduardo.dbeaver.aichat.core.llm.ToolSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolRegistry {

    private final Map<String, DbTool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<DbTool> list) {
        for (DbTool tool : list) {
            tools.put(tool.name(), tool);
        }
    }

    public static ToolRegistry standard(DatabaseGateway gateway) {
        return new ToolRegistry(List.of(
                new ListConnectionsTool(gateway),
                new ListSchemasTool(gateway),
                new ListTablesTool(gateway),
                new DescribeTableTool(gateway),
                new RunQueryTool(gateway),
                new ExecuteWriteTool(gateway)));
    }

    public List<ToolSpec> specs() {
        return tools.values().stream().map(DbTool::spec).toList();
    }

    /** Never throws except CancelledException; every failure becomes an error outcome for the model. */
    public ToolOutcome execute(ToolCall call, ToolContext ctx) {
        DbTool tool = tools.get(call.name());
        if (tool == null) {
            return ToolOutcome.error("Ferramenta desconhecida: " + call.name());
        }
        JsonObject args;
        try {
            String raw = call.argumentsJson() == null || call.argumentsJson().isBlank() ? "{}" : call.argumentsJson();
            JsonElement parsed = JsonParser.parseString(raw);
            if (!parsed.isJsonObject()) {
                throw new IllegalStateException("arguments must be a JSON object");
            }
            args = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            JsonObject err = new JsonObject();
            err.addProperty("INVALID_JSON", String.valueOf(call.argumentsJson()));
            return ToolOutcome.error(err.toString());
        }
        try {
            return tool.execute(args, ctx);
        } catch (CancelledException e) {
            throw e;
        } catch (ToolException | GatewayException e) {
            return ToolOutcome.error(e.getMessage());
        } catch (RuntimeException e) {
            return ToolOutcome.error("Erro interno na ferramenta " + call.name() + ": " + e);
        }
    }
}
```

- [ ] **Step 5: Implementar as seis tools**

`C/tools/ListConnectionsTool.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

import com.google.gson.JsonObject;
import io.github.eduardo.dbeaver.aichat.core.Json;
import java.util.List;

final class ListConnectionsTool implements DbTool {

    private final DatabaseGateway gateway;

    ListConnectionsTool(DatabaseGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String name() {
        return "list_connections";
    }

    @Override
    public String description() {
        return "List the database connections configured in DBeaver, with id, driver, connection state and read-only flag.";
    }

    @Override
    public JsonObject inputSchema() {
        return Json.parseObject("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}");
    }

    @Override
    public ToolOutcome execute(JsonObject args, ToolContext ctx) {
        List<ConnectionInfo> all = gateway.listConnections();
        if (all.isEmpty()) {
            return ToolOutcome.ok("Nenhuma conexão configurada no DBeaver.");
        }
        StringBuilder sb = new StringBuilder("Conexões (").append(all.size()).append("):\n");
        for (ConnectionInfo c : all) {
            sb.append("- ").append(c.name()).append(" (id: ").append(c.id())
                    .append(", driver: ").append(c.driver())
                    .append(", projeto: ").append(c.project())
                    .append(c.connected() ? ", conectada" : ", desconectada")
                    .append(c.readOnly() ? ", somente leitura" : "")
                    .append(")\n");
        }
        return ToolOutcome.ok(sb.toString());
    }
}
```

`C/tools/ListSchemasTool.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

import com.google.gson.JsonObject;
import io.github.eduardo.dbeaver.aichat.core.Json;
import java.util.List;

final class ListSchemasTool implements DbTool {

    private final DatabaseGateway gateway;

    ListSchemasTool(DatabaseGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String name() {
        return "list_schemas";
    }

    @Override
    public String description() {
        return "List catalogs/schemas of a connection (connects if needed). Returns paths usable as the schema parameter of other tools.";
    }

    @Override
    public JsonObject inputSchema() {
        return Json.parseObject("""
                {"type":"object","properties":{
                  "connection":{"type":"string","description":"Connection name or id. Defaults to the selected connection."}
                },"additionalProperties":false}""");
    }

    @Override
    public ToolOutcome execute(JsonObject args, ToolContext ctx) throws ToolException, GatewayException {
        ConnectionInfo conn = ConnectionResolver.resolve(gateway, Args.optString(args, "connection"), ctx.defaultConnectionId());
        List<String> schemas = gateway.listSchemas(conn.id());
        if (schemas.isEmpty()) {
            return ToolOutcome.ok("A conexão " + conn.name() + " não usa schemas; chame list_tables sem o parâmetro schema.");
        }
        StringBuilder sb = new StringBuilder("Schemas em ").append(conn.name()).append(":\n");
        int limit = ctx.limits().maxListItems();
        for (int i = 0; i < Math.min(limit, schemas.size()); i++) {
            sb.append("- ").append(schemas.get(i)).append('\n');
        }
        if (schemas.size() > limit) {
            sb.append("... ").append(schemas.size() - limit).append(" schema(s) omitidos.\n");
        }
        return ToolOutcome.ok(sb.toString());
    }
}
```

`C/tools/ListTablesTool.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

import com.google.gson.JsonObject;
import io.github.eduardo.dbeaver.aichat.core.Json;
import java.util.List;

final class ListTablesTool implements DbTool {

    private final DatabaseGateway gateway;

    ListTablesTool(DatabaseGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String name() {
        return "list_tables";
    }

    @Override
    public String description() {
        return "List tables and views in a schema.";
    }

    @Override
    public JsonObject inputSchema() {
        return Json.parseObject("""
                {"type":"object","properties":{
                  "connection":{"type":"string","description":"Connection name or id. Defaults to the selected connection."},
                  "schema":{"type":"string","description":"Schema path as returned by list_schemas (e.g. public or mydb.dbo). Omit to use the default schema."},
                  "filter":{"type":"string","description":"Case-insensitive substring of the table name."}
                },"additionalProperties":false}""");
    }

    @Override
    public ToolOutcome execute(JsonObject args, ToolContext ctx) throws ToolException, GatewayException {
        ConnectionInfo conn = ConnectionResolver.resolve(gateway, Args.optString(args, "connection"), ctx.defaultConnectionId());
        String schema = Args.optString(args, "schema");
        String filter = Args.optString(args, "filter");
        int limit = ctx.limits().maxListItems();
        List<TableInfo> tables = gateway.listTables(conn.id(), schema, filter, limit + 1);
        String where = conn.name() + (schema != null ? " / " + schema : "");
        if (tables.isEmpty()) {
            return ToolOutcome.ok("Nenhuma tabela encontrada em " + where + ".");
        }
        StringBuilder sb = new StringBuilder("Tabelas em ").append(where).append(":\n");
        for (int i = 0; i < Math.min(limit, tables.size()); i++) {
            TableInfo t = tables.get(i);
            sb.append("- ").append(t.name()).append(" [").append(t.type()).append("]\n");
        }
        if (tables.size() > limit) {
            sb.append("... mais resultados omitidos; use o parâmetro filter para refinar.\n");
        }
        return ToolOutcome.ok(sb.toString());
    }
}
```

`C/tools/DescribeTableTool.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

import com.google.gson.JsonObject;
import io.github.eduardo.dbeaver.aichat.core.Json;

final class DescribeTableTool implements DbTool {

    private final DatabaseGateway gateway;

    DescribeTableTool(DatabaseGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String name() {
        return "describe_table";
    }

    @Override
    public String description() {
        return "Describe a table: columns with types, nullability and defaults, primary key, foreign keys and indexes.";
    }

    @Override
    public JsonObject inputSchema() {
        return Json.parseObject("""
                {"type":"object","properties":{
                  "connection":{"type":"string","description":"Connection name or id. Defaults to the selected connection."},
                  "schema":{"type":"string","description":"Schema path as returned by list_schemas. Omit to use the default schema."},
                  "table":{"type":"string","description":"Table or view name."}
                },"required":["table"],"additionalProperties":false}""");
    }

    @Override
    public ToolOutcome execute(JsonObject args, ToolContext ctx) throws ToolException, GatewayException {
        ConnectionInfo conn = ConnectionResolver.resolve(gateway, Args.optString(args, "connection"), ctx.defaultConnectionId());
        String table = Args.requireString(args, "table");
        TableDescription d = gateway.describeTable(conn.id(), Args.optString(args, "schema"), table);
        StringBuilder sb = new StringBuilder("Tabela ");
        if (d.schema() != null && !d.schema().isBlank()) {
            sb.append(d.schema()).append('.');
        }
        sb.append(d.name()).append(" (").append(d.type()).append(")\nColunas:\n");
        for (ColumnInfo c : d.columns()) {
            sb.append("- ").append(c.name()).append(' ').append(c.type()).append(c.nullable() ? " NULL" : " NOT NULL");
            if (c.defaultValue() != null && !c.defaultValue().isBlank()) {
                sb.append(" DEFAULT ").append(c.defaultValue());
            }
            sb.append('\n');
        }
        if (!d.primaryKey().isEmpty()) {
            sb.append("Chave primária: ").append(String.join(", ", d.primaryKey())).append('\n');
        }
        if (!d.foreignKeys().isEmpty()) {
            sb.append("Chaves estrangeiras:\n");
            for (ForeignKeyInfo fk : d.foreignKeys()) {
                sb.append("- ").append(fk.name()).append(" (").append(String.join(", ", fk.columns())).append(") -> ")
                        .append(fk.referencedTable()).append(" (").append(String.join(", ", fk.referencedColumns())).append(")\n");
            }
        }
        if (!d.indexes().isEmpty()) {
            sb.append("Índices:\n");
            for (IndexInfo idx : d.indexes()) {
                sb.append("- ").append(idx.name()).append(idx.unique() ? " UNIQUE" : "")
                        .append(" (").append(String.join(", ", idx.columns())).append(")\n");
            }
        }
        return ToolOutcome.ok(sb.toString());
    }
}
```

`C/tools/RunQueryTool.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

import com.google.gson.JsonObject;
import io.github.eduardo.dbeaver.aichat.core.Json;
import io.github.eduardo.dbeaver.aichat.core.sql.SqlClassifier;
import io.github.eduardo.dbeaver.aichat.core.sql.SqlClassifier.SqlKind;

final class RunQueryTool implements DbTool {

    private final DatabaseGateway gateway;

    RunQueryTool(DatabaseGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String name() {
        return "run_query";
    }

    @Override
    public String description() {
        return "Run ONE read-only SQL statement (SELECT, SHOW, EXPLAIN...) and return up to max_rows rows as text. "
                + "Statements that modify data are rejected; use execute_write for those.";
    }

    @Override
    public JsonObject inputSchema() {
        return Json.parseObject("""
                {"type":"object","properties":{
                  "connection":{"type":"string","description":"Connection name or id. Defaults to the selected connection."},
                  "sql":{"type":"string","description":"A single read-only SQL statement."},
                  "max_rows":{"type":"integer","description":"Maximum rows to return (capped by the user's settings)."}
                },"required":["sql"],"additionalProperties":false}""");
    }

    @Override
    public ToolOutcome execute(JsonObject args, ToolContext ctx) throws ToolException, GatewayException {
        ConnectionInfo conn = ConnectionResolver.resolve(gateway, Args.optString(args, "connection"), ctx.defaultConnectionId());
        String sql = Args.requireString(args, "sql");
        boolean read = SqlClassifier.classify(sql) == SqlKind.READ
                && gateway.classifyNative(conn.id(), sql).orElse(SqlKind.READ) == SqlKind.READ;
        if (!read) {
            throw new ToolException("Este SQL modifica dados ou não pôde ser confirmado como somente leitura. "
                    + "Use execute_write (exige aprovação do usuário) ou reescreva como um único SELECT.");
        }
        Integer requested = Args.optInt(args, "max_rows");
        int maxRows = ctx.limits().maxRows();
        if (requested != null && requested > 0) {
            maxRows = Math.min(requested, maxRows);
        }
        QueryResult result = gateway.runReadQuery(conn.id(), sql, maxRows, ctx.cancel());
        String header = result.rows().size() + " linha(s)"
                + (result.truncated() ? " (limite de " + maxRows + " atingido; pode haver mais)" : "") + "\n";
        return ToolOutcome.ok(header + TextTable.format(result, ctx.limits().maxResultChars()));
    }
}
```

`C/tools/ExecuteWriteTool.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.tools;

import com.google.gson.JsonObject;
import io.github.eduardo.dbeaver.aichat.core.Json;
import io.github.eduardo.dbeaver.aichat.core.sql.SqlClassifier;

final class ExecuteWriteTool implements DbTool {

    private final DatabaseGateway gateway;

    ExecuteWriteTool(DatabaseGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String name() {
        return "execute_write";
    }

    @Override
    public String description() {
        return "Run ONE SQL statement that modifies data or schema (INSERT, UPDATE, DELETE, DDL...). "
                + "The user must approve it in the chat before it runs.";
    }

    @Override
    public JsonObject inputSchema() {
        return Json.parseObject("""
                {"type":"object","properties":{
                  "connection":{"type":"string","description":"Connection name or id. Defaults to the selected connection."},
                  "sql":{"type":"string","description":"A single SQL statement."},
                  "reason":{"type":"string","description":"Short explanation shown to the user in the approval request."}
                },"required":["sql","reason"],"additionalProperties":false}""");
    }

    @Override
    public ToolOutcome execute(JsonObject args, ToolContext ctx) throws ToolException, GatewayException {
        ConnectionInfo conn = ConnectionResolver.resolve(gateway, Args.optString(args, "connection"), ctx.defaultConnectionId());
        String sql = Args.requireString(args, "sql");
        String reason = Args.optString(args, "reason");
        if (conn.readOnly()) {
            throw new ToolException("A conexão " + conn.name() + " está marcada como somente leitura no DBeaver; escrita bloqueada.");
        }
        if (SqlClassifier.statementCount(sql) > 1) {
            throw new ToolException("Envie um único comando SQL por chamada de execute_write.");
        }
        Decision decision = ctx.approvalGate().requestApproval(new ApprovalRequest(conn.name(), sql, reason), ctx.cancel());
        if (decision != Decision.APPROVED) {
            return ToolOutcome.error("O usuário rejeitou a execução. Não tente de novo sem perguntar ao usuário.");
        }
        long count = gateway.executeUpdate(conn.id(), sql, ctx.cancel());
        return ToolOutcome.ok("Executado com sucesso na conexão " + conn.name() + ". Linhas afetadas: "
                + (count < 0 ? "não informado" : count));
    }
}
```

- [ ] **Step 6: Rodar e ver passar**

Run: `mvn -q -f core/pom.xml test -Dtest='ToolsTest,TextTableTest'`
Expected: `Tests run: 20, Failures: 0, Errors: 0`.

- [ ] **Step 7: Commit**

```bash
git add core/src
git commit -m "feat(core): database tools with approval gate and result truncation" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Loop agêntico (Conversation, ChatSession, SystemPromptBuilder)

**Files:**
- Create: `C/agent/Conversation.java`, `C/agent/SessionSettings.java`, `C/agent/SessionListener.java`, `C/agent/ChatSession.java`, `C/agent/PromptContext.java`, `C/agent/SystemPromptBuilder.java`
- Test: `T/agent/ScriptedProvider.java`, `T/agent/RecordingListener.java`, `T/agent/ChatSessionTest.java`, `T/agent/SystemPromptBuilderTest.java`

**Interfaces:**
- Consumes: `LlmProvider`, `ChatRequest`, `AssistantTurn`, `ChatMessage`, `ProviderConfig` (Task 2), `ToolRegistry`, `ToolContext`, `ToolOutcome`, `ConnectionInfo` (Task 6).
- Produces:
  - `Conversation.create()`, `add(ChatMessage)`, `messages()` (cópia imutável), `id()`, `title()`, `createdAt()`, `updatedAt()`, `providerId()`/`setProviderId`, `connectionId()`/`setConnectionId`; `Conversation.DEFAULT_TITLE = "Nova conversa"`
  - `record SessionSettings(int maxIterations)` + `SessionSettings.DEFAULT = new SessionSettings(10)`
  - `interface SessionListener { onAssistantStart(); onTextDelta(String); onAssistantEnd(AssistantTurn); onToolStart(ToolCall); onToolEnd(ToolCall, ToolOutcome); onNotice(String); onError(String); onTurnFinished(); }`
  - `new ChatSession(Conversation, LlmProvider, ProviderConfig, ToolRegistry, SessionSettings)`; `runTurn(String userText, String systemPrompt, ToolContext, SessionListener)` — nunca lança; sempre chama `onTurnFinished()`
  - `record PromptContext(ConnectionInfo connection, String dialect, String defaultSchema, String selectedSql, int maxRows)`; `SystemPromptBuilder.build(PromptContext)` → `String`

Invariante: depois de `runTurn`, cada `ToolCall` de cada mensagem do assistente tem um `ChatMessage.toolResult` correspondente no histórico (executado, rejeitado, cancelado ou "não executado").

- [ ] **Step 1: Escrever helpers de teste e testes**

`T/agent/ScriptedProvider.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.agent;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.llm.AssistantTurn;
import io.github.eduardo.dbeaver.aichat.core.llm.ChatRequest;
import io.github.eduardo.dbeaver.aichat.core.llm.LlmException;
import io.github.eduardo.dbeaver.aichat.core.llm.LlmProvider;
import io.github.eduardo.dbeaver.aichat.core.llm.StopReason;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/** Returns pre-recorded turns (or throws pre-recorded errors) in order. */
final class ScriptedProvider implements LlmProvider {

    private final Deque<Object> script = new ArrayDeque<>();
    final List<ChatRequest> requests = new ArrayList<>();
    AssistantTurn repeat;

    ScriptedProvider then(AssistantTurn turn) {
        script.add(turn);
        return this;
    }

    ScriptedProvider thenFail(LlmException e) {
        script.add(e);
        return this;
    }

    @Override
    public AssistantTurn stream(ChatRequest request, Consumer<String> onTextDelta, CancellationToken cancel)
            throws LlmException {
        requests.add(request);
        Object next = !script.isEmpty() ? script.poll()
                : repeat != null ? repeat
                : new AssistantTurn("fim", List.of(), StopReason.END_TURN, null, null);
        if (next instanceof LlmException e) {
            throw e;
        }
        AssistantTurn turn = (AssistantTurn) next;
        if (!turn.text().isEmpty()) {
            onTextDelta.accept(turn.text());
        }
        return turn;
    }
}
```

`T/agent/RecordingListener.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.agent;

import io.github.eduardo.dbeaver.aichat.core.llm.AssistantTurn;
import io.github.eduardo.dbeaver.aichat.core.llm.ToolCall;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolOutcome;
import java.util.ArrayList;
import java.util.List;

final class RecordingListener implements SessionListener {

    final List<String> events = new ArrayList<>();

    @Override
    public void onAssistantStart() {
        events.add("start");
    }

    @Override
    public void onTextDelta(String text) {
        events.add("text:" + text);
    }

    @Override
    public void onAssistantEnd(AssistantTurn turn) {
        events.add("end:" + turn.stopReason());
    }

    @Override
    public void onToolStart(ToolCall call) {
        events.add("tool:" + call.name());
    }

    @Override
    public void onToolEnd(ToolCall call, ToolOutcome outcome) {
        events.add("toolEnd:" + call.name() + (outcome.error() ? ":error" : ""));
    }

    @Override
    public void onNotice(String message) {
        events.add("notice:" + message);
    }

    @Override
    public void onError(String message) {
        events.add("error:" + message);
    }

    @Override
    public void onTurnFinished() {
        events.add("finished");
    }
}
```

`T/agent/ChatSessionTest.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.agent;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.Json;
import io.github.eduardo.dbeaver.aichat.core.llm.AssistantTurn;
import io.github.eduardo.dbeaver.aichat.core.llm.ChatMessage;
import io.github.eduardo.dbeaver.aichat.core.llm.LlmException;
import io.github.eduardo.dbeaver.aichat.core.llm.ProviderConfig;
import io.github.eduardo.dbeaver.aichat.core.llm.ProviderType;
import io.github.eduardo.dbeaver.aichat.core.llm.Role;
import io.github.eduardo.dbeaver.aichat.core.llm.StopReason;
import io.github.eduardo.dbeaver.aichat.core.llm.ToolCall;
import io.github.eduardo.dbeaver.aichat.core.tools.DbTool;
import io.github.eduardo.dbeaver.aichat.core.tools.Decision;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolContext;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolLimits;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolOutcome;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ChatSessionTest {

    private static final ProviderConfig CONFIG =
            new ProviderConfig("p", "p", ProviderType.OPENAI_COMPATIBLE, "http://x", "m", 0, null, false);

    private static DbTool probe(Function<ToolContext, ToolOutcome> body) {
        return new DbTool() {
            @Override
            public String name() {
                return "probe";
            }

            @Override
            public String description() {
                return "test tool";
            }

            @Override
            public JsonObject inputSchema() {
                return Json.parseObject("{\"type\":\"object\",\"properties\":{}}");
            }

            @Override
            public ToolOutcome execute(JsonObject args, ToolContext ctx) {
                return body.apply(ctx);
            }
        };
    }

    private static AssistantTurn toolTurn(String... ids) {
        List<ToolCall> calls = new ArrayList<>();
        for (String id : ids) {
            calls.add(new ToolCall(id, "probe", "{}"));
        }
        return new AssistantTurn("", calls, StopReason.TOOL_USE, null, null);
    }

    private static AssistantTurn text(String t) {
        return new AssistantTurn(t, List.of(), StopReason.END_TURN, null, null);
    }

    private static ToolContext ctx(CancellationToken token) {
        return new ToolContext("db", (r, c) -> Decision.APPROVED, token, ToolLimits.DEFAULT);
    }

    private static List<Role> roles(Conversation c) {
        return c.messages().stream().map(ChatMessage::role).toList();
    }

    @Test
    void finalAnswerWithoutTools() {
        Conversation conversation = Conversation.create();
        ScriptedProvider provider = new ScriptedProvider().then(text("Olá!"));
        RecordingListener listener = new RecordingListener();
        new ChatSession(conversation, provider, CONFIG, new ToolRegistry(List.of()), SessionSettings.DEFAULT)
                .runTurn("Oi", "sys", ctx(new CancellationToken()), listener);

        assertEquals(List.of(Role.USER, Role.ASSISTANT), roles(conversation));
        assertEquals(List.of("start", "text:Olá!", "end:END_TURN", "finished"), listener.events);
        assertEquals("sys", provider.requests.get(0).systemPrompt());
        assertEquals("Oi", conversation.title());
    }

    @Test
    void runsToolsAndLoopsUntilFinalAnswer() {
        Conversation conversation = Conversation.create();
        ScriptedProvider provider = new ScriptedProvider().then(toolTurn("t1")).then(text("Pronto"));
        RecordingListener listener = new RecordingListener();
        new ChatSession(conversation, provider, CONFIG, new ToolRegistry(List.of(probe(c -> ToolOutcome.ok("42")))),
                SessionSettings.DEFAULT).runTurn("Conte", "sys", ctx(new CancellationToken()), listener);

        assertEquals(List.of(Role.USER, Role.ASSISTANT, Role.TOOL, Role.ASSISTANT), roles(conversation));
        ChatMessage result = conversation.messages().get(2);
        assertEquals("t1", result.toolCallId());
        assertEquals("42", result.text());
        assertEquals(2, provider.requests.size());
        assertEquals(3, provider.requests.get(1).messages().size());
        assertEquals("probe", provider.requests.get(0).tools().get(0).name());
        assertTrue(listener.events.contains("tool:probe"));
        assertTrue(listener.events.contains("toolEnd:probe"));
    }

    @Test
    void doesNotRunToolsWhenResponseWasTruncated() {
        Conversation conversation = Conversation.create();
        AssistantTurn truncated = new AssistantTurn("", List.of(new ToolCall("t1", "probe", "{\"a\":")),
                StopReason.MAX_TOKENS, null, null);
        List<String> executed = new ArrayList<>();
        RecordingListener listener = new RecordingListener();
        new ChatSession(conversation, new ScriptedProvider().then(truncated), CONFIG,
                new ToolRegistry(List.of(probe(c -> {
                    executed.add("x");
                    return ToolOutcome.ok("");
                }))), SessionSettings.DEFAULT)
                .runTurn("Oi", "sys", ctx(new CancellationToken()), listener);

        assertTrue(executed.isEmpty());
        assertEquals(List.of(Role.USER, Role.ASSISTANT, Role.TOOL), roles(conversation));
        assertTrue(conversation.messages().get(2).error());
        assertTrue(listener.events.stream().anyMatch(e -> e.startsWith("notice:") && e.contains("max_tokens")));
    }

    @Test
    void stopsAtIterationLimit() {
        Conversation conversation = Conversation.create();
        ScriptedProvider provider = new ScriptedProvider();
        provider.repeat = toolTurn("t");
        RecordingListener listener = new RecordingListener();
        new ChatSession(conversation, provider, CONFIG, new ToolRegistry(List.of(probe(c -> ToolOutcome.ok("ok")))),
                new SessionSettings(3)).runTurn("Oi", "sys", ctx(new CancellationToken()), listener);

        assertEquals(3, provider.requests.size());
        assertTrue(listener.events.stream().anyMatch(e -> e.startsWith("notice:Limite de 3")));
        assertEquals("finished", listener.events.get(listener.events.size() - 1));
    }

    @Test
    void cancellationDuringToolsClosesEveryPendingCall() {
        Conversation conversation = Conversation.create();
        CancellationToken token = new CancellationToken();
        ScriptedProvider provider = new ScriptedProvider().then(toolTurn("t1", "t2", "t3"));
        RecordingListener listener = new RecordingListener();
        new ChatSession(conversation, provider, CONFIG, new ToolRegistry(List.of(probe(c -> {
            c.cancel().cancel();
            return ToolOutcome.ok("feito");
        }))), SessionSettings.DEFAULT).runTurn("Oi", "sys", ctx(token), listener);

        List<ChatMessage> results = conversation.messages().stream().filter(m -> m.role() == Role.TOOL).toList();
        assertEquals(List.of("t1", "t2", "t3"), results.stream().map(ChatMessage::toolCallId).toList());
        assertEquals("feito", results.get(0).text());
        assertTrue(results.get(1).error());
        assertTrue(listener.events.contains("notice:Cancelado."));
        assertEquals(1, provider.requests.size());
    }

    @Test
    void providerErrorIsReportedAndUserMessageKept() {
        Conversation conversation = Conversation.create();
        RecordingListener listener = new RecordingListener();
        new ChatSession(conversation, new ScriptedProvider().thenFail(new LlmException("HTTP 401: chave inválida", 401)),
                CONFIG, new ToolRegistry(List.of()), SessionSettings.DEFAULT)
                .runTurn("Oi", "sys", ctx(new CancellationToken()), listener);

        assertEquals(List.of(Role.USER), roles(conversation));
        assertTrue(listener.events.contains("error:HTTP 401: chave inválida"));
        assertEquals("finished", listener.events.get(listener.events.size() - 1));
    }
}
```

`T/agent/SystemPromptBuilderTest.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.github.eduardo.dbeaver.aichat.core.tools.ConnectionInfo;
import org.junit.jupiter.api.Test;

class SystemPromptBuilderTest {

    @Test
    void includesConnectionDialectSchemaAndSelection() {
        ConnectionInfo conn = new ConnectionInfo("pg-1", "prod-pg", "PostgreSQL", "General", true, true);
        String prompt = SystemPromptBuilder.build(new PromptContext(conn, "PostgreSQL", "public", "select * from orders", 200));
        assertTrue(prompt.contains("prod-pg (id: pg-1, driver: PostgreSQL)"));
        assertTrue(prompt.contains("READ-ONLY"));
        assertTrue(prompt.contains("SQL dialect: PostgreSQL"));
        assertTrue(prompt.contains("Default schema: public"));
        assertTrue(prompt.contains("```sql\nselect * from orders\n```"));
        assertTrue(prompt.contains("limited to 200 rows"));
    }

    @Test
    void withoutConnectionAsksToList() {
        String prompt = SystemPromptBuilder.build(new PromptContext(null, null, null, null, 50));
        assertTrue(prompt.contains("No default connection"));
        assertFalse(prompt.contains("SQL dialect"));
    }

    @Test
    void truncatesHugeSelection() {
        String huge = "x".repeat(20_000);
        String prompt = SystemPromptBuilder.build(new PromptContext(null, null, null, huge, 50));
        assertTrue(prompt.contains("-- [truncated]"));
        assertTrue(prompt.length() < 12_000);
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `mvn -q -f core/pom.xml test -Dtest='ChatSessionTest,SystemPromptBuilderTest'`
Expected: FAIL de compilação (`Conversation`, `ChatSession`, ... não existem).

- [ ] **Step 3: Implementar**

`C/agent/Conversation.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.agent;

import io.github.eduardo.dbeaver.aichat.core.llm.ChatMessage;
import io.github.eduardo.dbeaver.aichat.core.llm.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A chat thread. Serialized as-is by ConversationStore (Gson, field access). */
public final class Conversation {

    public static final String DEFAULT_TITLE = "Nova conversa";
    private static final int TITLE_LENGTH = 60;

    private String id;
    private String title;
    private long createdAt;
    private long updatedAt;
    private String providerId;
    private String connectionId;
    private List<ChatMessage> messages = new ArrayList<>();

    public static Conversation create() {
        Conversation c = new Conversation();
        c.id = UUID.randomUUID().toString();
        c.title = DEFAULT_TITLE;
        c.createdAt = System.currentTimeMillis();
        c.updatedAt = c.createdAt;
        return c;
    }

    public synchronized void add(ChatMessage message) {
        messages.add(message);
        updatedAt = System.currentTimeMillis();
        if (message.role() == Role.USER && DEFAULT_TITLE.equals(title)) {
            String t = message.text().strip().replaceAll("\\s+", " ");
            title = t.isEmpty() ? DEFAULT_TITLE : t.length() > TITLE_LENGTH ? t.substring(0, TITLE_LENGTH) + "…" : t;
        }
    }

    public synchronized List<ChatMessage> messages() {
        return List.copyOf(messages);
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public long createdAt() {
        return createdAt;
    }

    public long updatedAt() {
        return updatedAt;
    }

    public String providerId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String connectionId() {
        return connectionId;
    }

    public void setConnectionId(String connectionId) {
        this.connectionId = connectionId;
    }
}
```

`C/agent/SessionSettings.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.agent;

/** maxIterations = maximum model calls per user message. */
public record SessionSettings(int maxIterations) {

    public static final SessionSettings DEFAULT = new SessionSettings(10);
}
```

`C/agent/SessionListener.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.agent;

import io.github.eduardo.dbeaver.aichat.core.llm.AssistantTurn;
import io.github.eduardo.dbeaver.aichat.core.llm.ToolCall;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolOutcome;

/** Called from the worker thread running the turn. */
public interface SessionListener {

    void onAssistantStart();

    void onTextDelta(String text);

    void onAssistantEnd(AssistantTurn turn);

    void onToolStart(ToolCall call);

    void onToolEnd(ToolCall call, ToolOutcome outcome);

    void onNotice(String message);

    void onError(String message);

    void onTurnFinished();
}
```

`C/agent/ChatSession.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.agent;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.CancelledException;
import io.github.eduardo.dbeaver.aichat.core.llm.AssistantTurn;
import io.github.eduardo.dbeaver.aichat.core.llm.ChatMessage;
import io.github.eduardo.dbeaver.aichat.core.llm.ChatRequest;
import io.github.eduardo.dbeaver.aichat.core.llm.LlmException;
import io.github.eduardo.dbeaver.aichat.core.llm.LlmProvider;
import io.github.eduardo.dbeaver.aichat.core.llm.ProviderConfig;
import io.github.eduardo.dbeaver.aichat.core.llm.StopReason;
import io.github.eduardo.dbeaver.aichat.core.llm.ToolCall;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolContext;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolOutcome;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolRegistry;
import java.util.List;

/** Runs one user turn: model -> tools -> model ... until a final answer, a stop, or the iteration limit. */
public final class ChatSession {

    private final Conversation conversation;
    private final LlmProvider provider;
    private final ProviderConfig config;
    private final ToolRegistry tools;
    private final SessionSettings settings;

    public ChatSession(Conversation conversation, LlmProvider provider, ProviderConfig config, ToolRegistry tools,
            SessionSettings settings) {
        this.conversation = conversation;
        this.provider = provider;
        this.config = config;
        this.tools = tools;
        this.settings = settings;
    }

    public void runTurn(String userText, String systemPrompt, ToolContext toolContext, SessionListener listener) {
        CancellationToken cancel = toolContext.cancel();
        conversation.add(ChatMessage.user(userText));
        try {
            for (int iteration = 0; iteration < settings.maxIterations(); iteration++) {
                cancel.throwIfCancelled();
                ChatRequest request = new ChatRequest(config.model(), systemPrompt, conversation.messages(),
                        tools.specs(), config.maxTokens(), config.temperature());
                listener.onAssistantStart();
                AssistantTurn turn = provider.stream(request, listener::onTextDelta, cancel);
                if (!turn.isEmpty()) {
                    conversation.add(ChatMessage.assistant(turn));
                }
                listener.onAssistantEnd(turn);
                if (turn.toolCalls().isEmpty()) {
                    notifyStop(turn.stopReason(), listener);
                    return;
                }
                if (turn.stopReason() != StopReason.TOOL_USE) {
                    closeAll(turn.toolCalls(), "Não executado: a resposta do modelo foi interrompida ("
                            + turn.stopReason() + ").");
                    notifyStop(turn.stopReason(), listener);
                    return;
                }
                runTools(turn.toolCalls(), toolContext, listener);
            }
            listener.onNotice("Limite de " + settings.maxIterations() + " chamadas ao modelo atingido nesta pergunta.");
        } catch (CancelledException e) {
            listener.onNotice("Cancelado.");
        } catch (LlmException e) {
            listener.onError(e.getMessage());
        } catch (RuntimeException e) {
            listener.onError("Erro inesperado: " + e);
        } finally {
            listener.onTurnFinished();
        }
    }

    private void runTools(List<ToolCall> calls, ToolContext ctx, SessionListener listener) {
        for (int i = 0; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            if (ctx.cancel().isCancelled()) {
                closeAll(calls.subList(i, calls.size()), "Cancelado pelo usuário.");
                throw new CancelledException();
            }
            listener.onToolStart(call);
            ToolOutcome outcome;
            try {
                outcome = tools.execute(call, ctx);
            } catch (CancelledException e) {
                outcome = ToolOutcome.error("Cancelado pelo usuário.");
                conversation.add(ChatMessage.toolResult(call.id(), outcome.content(), true));
                listener.onToolEnd(call, outcome);
                closeAll(calls.subList(i + 1, calls.size()), "Cancelado pelo usuário.");
                throw e;
            }
            conversation.add(ChatMessage.toolResult(call.id(), outcome.content(), outcome.error()));
            listener.onToolEnd(call, outcome);
        }
        ctx.cancel().throwIfCancelled();
    }

    private void closeAll(List<ToolCall> calls, String reason) {
        for (ToolCall call : calls) {
            conversation.add(ChatMessage.toolResult(call.id(), reason, true));
        }
    }

    private static void notifyStop(StopReason reason, SessionListener listener) {
        if (reason == StopReason.MAX_TOKENS) {
            listener.onNotice("Resposta cortada pelo limite de tokens (max_tokens).");
        } else if (reason == StopReason.REFUSAL) {
            listener.onNotice("O modelo recusou esta solicitação.");
        }
    }
}
```

Nota sobre `cancellationDuringToolsClosesEveryPendingCall`: a tool cancela o token e retorna "feito"; o loop registra o resultado de `t1`, vê o token cancelado antes de `t2`, fecha `t2` e `t3` com erro e lança `CancelledException`, que vira o aviso "Cancelado.".

`C/agent/PromptContext.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.agent;

import io.github.eduardo.dbeaver.aichat.core.tools.ConnectionInfo;

/** Everything the system prompt mentions. Any field except maxRows may be null. */
public record PromptContext(ConnectionInfo connection, String dialect, String defaultSchema, String selectedSql,
        int maxRows) {
}
```

`C/agent/SystemPromptBuilder.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.agent;

import io.github.eduardo.dbeaver.aichat.core.tools.ConnectionInfo;

public final class SystemPromptBuilder {

    static final int MAX_SELECTED_SQL = 8000;

    private SystemPromptBuilder() {
    }

    public static String build(PromptContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are an AI assistant embedded in DBeaver, a database IDE. You help the user explore, query and change their databases through the tools provided.

                Rules:
                - Before writing SQL, use list_connections, list_schemas, list_tables and describe_table to learn the real structure. Never invent table or column names.
                - Use run_query only for read-only statements (SELECT, SHOW, EXPLAIN...). Results are limited to %d rows, so prefer aggregations, filters and LIMIT.
                - To change data or structure (INSERT, UPDATE, DELETE, DDL...), call execute_write with one statement and a short reason. The user must approve it in the chat; never say a change was made unless execute_write reported success.
                - Send exactly one SQL statement per tool call.
                - When you show SQL to the user, put it in a ```sql fenced code block.
                - Answer in the same language the user writes in.
                """.formatted(ctx.maxRows()));
        sb.append("\nContext:\n");
        ConnectionInfo c = ctx.connection();
        if (c == null) {
            sb.append("- No default connection is selected. Call list_connections and ask the user which one to use when it is ambiguous.\n");
        } else {
            sb.append("- Default connection: ").append(c.name()).append(" (id: ").append(c.id())
                    .append(", driver: ").append(c.driver()).append(")");
            if (c.readOnly()) {
                sb.append(" - marked READ-ONLY in DBeaver; writes will be refused");
            }
            sb.append('\n');
        }
        if (notBlank(ctx.dialect())) {
            sb.append("- SQL dialect: ").append(ctx.dialect()).append('\n');
        }
        if (notBlank(ctx.defaultSchema())) {
            sb.append("- Default schema: ").append(ctx.defaultSchema()).append('\n');
        }
        if (notBlank(ctx.selectedSql())) {
            String sql = ctx.selectedSql().length() > MAX_SELECTED_SQL
                    ? ctx.selectedSql().substring(0, MAX_SELECTED_SQL) + "\n-- [truncated]"
                    : ctx.selectedSql();
            sb.append("- SQL currently selected in the editor:\n```sql\n").append(sql).append("\n```\n");
        }
        return sb.toString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
```

- [ ] **Step 4: Rodar e ver passar**

Run: `mvn -q -f core/pom.xml test -Dtest='ChatSessionTest,SystemPromptBuilderTest'`
Expected: `Tests run: 9, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add core/src
git commit -m "feat(core): agent loop with tool execution, stop handling and system prompt" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Histórico persistente (ConversationStore)

**Files:**
- Create: `C/history/ConversationSummary.java`, `C/history/ConversationStore.java`
- Test: `T/history/ConversationStoreTest.java`

**Interfaces:**
- Consumes: `Conversation` (Task 7), `Json.GSON` (Task 2).
- Produces: `new ConversationStore(Path dir)`; `save(Conversation)`, `load(String id)`, `list()` → `List<ConversationSummary>` (mais recente primeiro, arquivos corrompidos ignorados), `delete(String id)`, todos `throws IOException`; ids fora do formato UUID → `IllegalArgumentException`. `record ConversationSummary(String id, String title, long updatedAt)`.

- [ ] **Step 1: Escrever os testes**

`T/history/ConversationStoreTest.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.history;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import io.github.eduardo.dbeaver.aichat.core.Json;
import io.github.eduardo.dbeaver.aichat.core.agent.Conversation;
import io.github.eduardo.dbeaver.aichat.core.llm.AssistantTurn;
import io.github.eduardo.dbeaver.aichat.core.llm.ChatMessage;
import io.github.eduardo.dbeaver.aichat.core.llm.StopReason;
import io.github.eduardo.dbeaver.aichat.core.llm.ToolCall;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationStoreTest {

    @TempDir
    Path dir;

    private static Conversation sample(String question) {
        Conversation c = Conversation.create();
        c.setProviderId("p1");
        c.setConnectionId("pg-1");
        c.add(ChatMessage.user(question));
        JsonArray raw = new JsonArray();
        raw.add(Json.parseObject("{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"abc\"}"));
        c.add(ChatMessage.assistant(new AssistantTurn("", List.of(new ToolCall("t1", "run_query", "{\"sql\":\"select 1\"}")),
                StopReason.TOOL_USE, raw, "anthropic")));
        c.add(ChatMessage.toolResult("t1", "1 linha(s)", false));
        return c;
    }

    @Test
    void roundTripKeepsMessagesAndRawContent() throws Exception {
        ConversationStore store = new ConversationStore(dir);
        Conversation original = sample("Quantos pedidos? Ação ✓");
        store.save(original);

        Conversation loaded = store.load(original.id());
        assertEquals(original.title(), loaded.title());
        assertEquals("p1", loaded.providerId());
        assertEquals("pg-1", loaded.connectionId());
        assertEquals(original.messages(), loaded.messages());
        assertEquals("abc", loaded.messages().get(1).rawContent().get(0).getAsJsonObject().get("signature").getAsString());
    }

    @Test
    void listIsSortedNewestFirstAndSkipsCorruptFiles() throws Exception {
        ConversationStore store = new ConversationStore(dir);
        Conversation older = sample("primeira");
        store.save(older);
        Thread.sleep(5);
        Conversation newer = sample("segunda");
        store.save(newer);
        Files.writeString(dir.resolve("00000000-0000-0000-0000-000000000000.json"), "{not json");

        List<ConversationSummary> list = store.list();
        assertEquals(List.of(newer.id(), older.id()), list.stream().map(ConversationSummary::id).toList());
        assertEquals("segunda", list.get(0).title());
    }

    @Test
    void deleteRemovesFile() throws Exception {
        ConversationStore store = new ConversationStore(dir);
        Conversation c = sample("x");
        store.save(c);
        store.delete(c.id());
        assertTrue(store.list().isEmpty());
    }

    @Test
    void rejectsIdsThatAreNotUuids() {
        ConversationStore store = new ConversationStore(dir);
        assertThrows(IllegalArgumentException.class, () -> store.load("../../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> store.delete("..\\x"));
    }

    @Test
    void listOnMissingDirectoryIsEmpty() throws Exception {
        assertTrue(new ConversationStore(dir.resolve("nao-existe")).list().isEmpty());
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `mvn -q -f core/pom.xml test -Dtest=ConversationStoreTest`
Expected: FAIL de compilação (`ConversationStore` não existe).

- [ ] **Step 3: Implementar**

`C/history/ConversationSummary.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.history;

public record ConversationSummary(String id, String title, long updatedAt) {
}
```

`C/history/ConversationStore.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.history;

import io.github.eduardo.dbeaver.aichat.core.Json;
import io.github.eduardo.dbeaver.aichat.core.agent.Conversation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** One JSON file per conversation: {dir}/{uuid}.json */
public final class ConversationStore {

    private static final Pattern ID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final Path dir;

    public ConversationStore(Path dir) {
        this.dir = dir;
    }

    public void save(Conversation conversation) throws IOException {
        Files.createDirectories(dir);
        Path target = file(conversation.id());
        Path tmp = dir.resolve(conversation.id() + ".json.tmp");
        String json;
        synchronized (conversation) {
            json = Json.GSON.toJson(conversation);
        }
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public Conversation load(String id) throws IOException {
        return read(file(id));
    }

    public List<ConversationSummary> list() throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<ConversationSummary> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.getFileName().toString().endsWith(".json"))::iterator) {
                try {
                    Conversation c = read(p);
                    if (c != null && c.id() != null) {
                        out.add(new ConversationSummary(c.id(), c.title(), c.updatedAt()));
                    }
                } catch (IOException | RuntimeException e) {
                    // corrupt or foreign file: not listed
                }
            }
        }
        out.sort(Comparator.comparingLong(ConversationSummary::updatedAt).reversed());
        return out;
    }

    public void delete(String id) throws IOException {
        Files.deleteIfExists(file(id));
    }

    private Path file(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Id de conversa inválido: " + id);
        }
        return dir.resolve(id + ".json");
    }

    private static Conversation read(Path p) throws IOException {
        return Json.GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), Conversation.class);
    }
}
```

- [ ] **Step 4: Rodar e ver passar**

Run: `mvn -q -f core/pom.xml test -Dtest=ConversationStoreTest`
Expected: `Tests run: 5, Failures: 0, Errors: 0`.

- [ ] **Step 5: Commit**

```bash
git add core/src
git commit -m "feat(core): JSON conversation store with atomic writes" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Renderização segura de markdown

**Files:**
- Create: `C/render/MarkdownRenderer.java`
- Test: `T/render/MarkdownRendererTest.java`

**Interfaces:**
- Produces: `new MarkdownRenderer()`, `toHtml(String markdown)` → HTML com HTML bruto escapado, URLs sanitizadas, tabelas GFM; blocos de código como `<pre><code class="language-sql">`.

- [ ] **Step 1: Escrever os testes**

`T/render/MarkdownRendererTest.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.render;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MarkdownRendererTest {

    private final MarkdownRenderer renderer = new MarkdownRenderer();

    @Test
    void escapesRawHtml() {
        String html = renderer.toHtml("Oi <script>alert(1)</script> <img src=x onerror=alert(1)>");
        assertFalse(html.contains("<script>"), html);
        assertFalse(html.contains("<img"), html);
        assertTrue(html.contains("&lt;script&gt;"), html);
    }

    @Test
    void sanitizesJavascriptLinks() {
        String html = renderer.toHtml("[clique](javascript:alert(1))");
        assertFalse(html.contains("javascript:"), html);
    }

    @Test
    void rendersSqlCodeBlocks() {
        String html = renderer.toHtml("```sql\nselect * from t where a < 1;\n```");
        assertTrue(html.contains("<pre><code class=\"language-sql\">select * from t where a &lt; 1;"), html);
    }

    @Test
    void rendersTables() {
        String html = renderer.toHtml("| a | b |\n|---|---|\n| 1 | 2 |\n");
        assertTrue(html.contains("<table>"), html);
        assertTrue(html.contains("<td>2</td>"), html);
    }

    @Test
    void nullIsEmpty() {
        assertEquals("", renderer.toHtml(null));
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `mvn -q -f core/pom.xml test -Dtest=MarkdownRendererTest`
Expected: FAIL de compilação (`MarkdownRenderer` não existe).

- [ ] **Step 3: Implementar**

`C/render/MarkdownRenderer.java`:

```java
package io.github.eduardo.dbeaver.aichat.core.render;

import java.util.List;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/** Markdown to HTML for the chat view. Raw HTML is escaped and unsafe URLs are dropped. */
public final class MarkdownRenderer {

    private final Parser parser;
    private final HtmlRenderer renderer;

    public MarkdownRenderer() {
        List<Extension> extensions = List.of(TablesExtension.create());
        parser = Parser.builder().extensions(extensions).build();
        renderer = HtmlRenderer.builder()
                .extensions(extensions)
                .escapeHtml(true)
                .sanitizeUrls(true)
                .build();
    }

    public String toHtml(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        return renderer.render(parser.parse(markdown));
    }
}
```

- [ ] **Step 4: Rodar a suíte inteira do core**

Run: `mvn -q -f core/pom.xml test`
Expected: `Tests run: 112, Failures: 0, Errors: 0` (12 + 7 + 10 + 44 + 20 + 9 + 5 + 5).

- [ ] **Step 5: Commit**

```bash
git add core/src
git commit -m "feat(core): safe markdown rendering with GFM tables" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

> Nas Tasks 10–13, `B/` = `bundles/io.github.eduardo.dbeaver.aichat/src/io/github/eduardo/dbeaver/aichat/`. Este código só roda dentro do DBeaver (OSGi + workbench), então o ciclo de teste destas tasks é: **compilar com `mvn clean verify`** (que também roda os 112 testes do core) e **validar manualmente no DBeaver** na Task 13. As assinaturas da API do DBeaver usadas aqui foram conferidas nos class files do DBeaver 24.1.4.

### Task 10: Integração com o DBeaver (gateway, compat, contexto do editor)

**Files:**
- Create: `B/dbeaver/DBeaverGateway.java`, `B/dbeaver/DBeaverCompat.java`, `B/dbeaver/EditorContext.java`, `B/dbeaver/EditorContextResolver.java`

**Interfaces:**
- Consumes: `DatabaseGateway` e records de `core.tools` (Task 6), `SqlClassifier.SqlKind` (Task 5), `CancellationToken` (Task 2).
- Produces:
  - `new DBeaverGateway()` implements `DatabaseGateway`; extras públicos: `DBPDataSourceContainer findContainer(String id)` (nullable), `String dialectName(String id)` (nullable, não conecta), `void close()`
  - `DBeaverCompat.classifyNative(DBPDataSource, String)` → `Optional<SqlKind>`; `DBeaverCompat.insertIntoActiveEditor(IWorkbenchWindow, String sql)` → `boolean`; `DBeaverCompat.openInNewEditor(IWorkbenchWindow, DBPDataSourceContainer, String sql)` → `boolean`
  - `record EditorContext(DBPDataSourceContainer container, String schema, String selectedText)`; `EditorContextResolver.fromActiveEditor(IWorkbenchWindow)` → `EditorContext` ou `null`

Comportamento do gateway:
- Leituras e escritas usam um **contexto de execução isolado** por conexão (`DBSInstance.openIsolatedContext`), com auto-commit desligado: leitura termina em `rollback`, escrita aprovada termina em `commit`. Assim o chat não interfere nas transações dos editores do usuário e uma leitura nunca persiste efeitos colaterais. Se o driver não suportar contexto isolado, cai no contexto padrão sem mexer no auto-commit.
- Timeout de 60 s por comando; cancelamento chama `DBCStatement.cancelBlock`.
- Células: `null` → `null`; `byte[]` → `<binário N bytes>`; texto acima de 2000 caracteres é cortado.

- [ ] **Step 1: Implementar `EditorContext` e `DBeaverCompat`**

`B/dbeaver/EditorContext.java`:

```java
package io.github.eduardo.dbeaver.aichat.dbeaver;

import org.jkiss.dbeaver.model.DBPDataSourceContainer;

/** Snapshot of the active SQL editor. container may be null (editor without connection). */
public record EditorContext(DBPDataSourceContainer container, String schema, String selectedText) {
}
```

`B/dbeaver/DBeaverCompat.java`:

```java
package io.github.eduardo.dbeaver.aichat.dbeaver;

import io.github.eduardo.dbeaver.aichat.core.sql.SqlClassifier.SqlKind;
import java.util.Optional;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.sql.SQLQuery;
import org.jkiss.dbeaver.model.sql.SQLQueryType;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;
import org.jkiss.dbeaver.ui.editors.sql.handlers.SQLEditorHandlerOpenEditor;
import org.jkiss.dbeaver.ui.editors.sql.handlers.SQLNavigatorContext;

/**
 * Every DBeaver call that is likely to change between versions lives here, guarded so that a
 * missing method degrades to a simpler behavior instead of breaking the plugin.
 */
public final class DBeaverCompat {

    private static final Log log = Log.getLog(DBeaverCompat.class);

    private DBeaverCompat() {
    }

    /** DBeaver's own SQL parser as a second opinion; empty when it has no opinion or is unavailable. */
    public static Optional<SqlKind> classifyNative(DBPDataSource dataSource, String sql) {
        try {
            SQLQuery query = new SQLQuery(dataSource, sql);
            SQLQueryType type = query.getType();
            if (type == null || type == SQLQueryType.UNKNOWN) {
                return Optional.empty();
            }
            if (type == SQLQueryType.SELECT) {
                return Optional.of(query.isModifying() ? SqlKind.WRITE : SqlKind.READ);
            }
            return Optional.of(SqlKind.WRITE);
        } catch (RuntimeException | LinkageError e) {
            log.debug("Native SQL classification unavailable", e);
            return Optional.empty();
        }
    }

    /** Inserts at the cursor (replacing the selection) of the active SQL editor. */
    public static boolean insertIntoActiveEditor(IWorkbenchWindow window, String sql) {
        IWorkbenchPage page = window == null ? null : window.getActivePage();
        IEditorPart part = page == null ? null : page.getActiveEditor();
        if (!(part instanceof SQLEditor)) {
            return false;
        }
        SQLEditor editor = (SQLEditor) part;
        try {
            IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
            ISelection selection = editor.getSelectionProvider().getSelection();
            int offset = document.getLength();
            int length = 0;
            if (selection instanceof ITextSelection) {
                offset = ((ITextSelection) selection).getOffset();
                length = ((ITextSelection) selection).getLength();
            }
            document.replace(offset, length, sql);
            page.activate(editor);
            return true;
        } catch (BadLocationException | RuntimeException | LinkageError e) {
            log.debug("Could not insert SQL into editor", e);
            return false;
        }
    }

    /** Opens a new SQL console bound to the connection with the given text. */
    public static boolean openInNewEditor(IWorkbenchWindow window, DBPDataSourceContainer container, String sql) {
        try {
            SQLNavigatorContext context = container == null ? new SQLNavigatorContext() : new SQLNavigatorContext(container);
            return SQLEditorHandlerOpenEditor.openSQLConsole(window, context, "AI Chat", sql) != null;
        } catch (RuntimeException | LinkageError e) {
            log.debug("Could not open SQL console", e);
            return false;
        }
    }
}
```

- [ ] **Step 2: Implementar `EditorContextResolver`**

`B/dbeaver/EditorContextResolver.java`:

```java
package io.github.eduardo.dbeaver.aichat.dbeaver;

import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;

public final class EditorContextResolver {

    private static final Log log = Log.getLog(EditorContextResolver.class);

    private EditorContextResolver() {
    }

    /** Context of the last active SQL editor in the window, or null if there is none. */
    public static EditorContext fromActiveEditor(IWorkbenchWindow window) {
        IWorkbenchPage page = window == null ? null : window.getActivePage();
        IEditorPart part = page == null ? null : page.getActiveEditor();
        if (!(part instanceof SQLEditor)) {
            return null;
        }
        SQLEditor editor = (SQLEditor) part;
        DBPDataSourceContainer container = editor.getDataSourceContainer();
        String schema = null;
        try {
            DBCExecutionContext context = editor.getExecutionContext();
            var defaults = context == null ? null : context.getContextDefaults();
            if (defaults != null) {
                var catalog = defaults.getDefaultCatalog();
                var defaultSchema = defaults.getDefaultSchema();
                if (defaultSchema != null) {
                    schema = catalog != null ? catalog.getName() + "." + defaultSchema.getName() : defaultSchema.getName();
                } else if (catalog != null) {
                    schema = catalog.getName();
                }
            }
        } catch (RuntimeException | LinkageError e) {
            log.debug("Could not read editor default schema", e);
        }
        String selected = null;
        ISelection selection = editor.getSelectionProvider().getSelection();
        if (selection instanceof ITextSelection && ((ITextSelection) selection).getLength() > 0) {
            selected = ((ITextSelection) selection).getText();
        }
        return new EditorContext(container, schema, selected);
    }
}
```

- [ ] **Step 3: Implementar `DBeaverGateway`**

`B/dbeaver/DBeaverGateway.java`:

```java
package io.github.eduardo.dbeaver.aichat.dbeaver;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.CancelledException;
import io.github.eduardo.dbeaver.aichat.core.sql.SqlClassifier.SqlKind;
import io.github.eduardo.dbeaver.aichat.core.tools.ColumnInfo;
import io.github.eduardo.dbeaver.aichat.core.tools.ConnectionInfo;
import io.github.eduardo.dbeaver.aichat.core.tools.DatabaseGateway;
import io.github.eduardo.dbeaver.aichat.core.tools.ForeignKeyInfo;
import io.github.eduardo.dbeaver.aichat.core.tools.GatewayException;
import io.github.eduardo.dbeaver.aichat.core.tools.IndexInfo;
import io.github.eduardo.dbeaver.aichat.core.tools.QueryResult;
import io.github.eduardo.dbeaver.aichat.core.tools.TableDescription;
import io.github.eduardo.dbeaver.aichat.core.tools.TableInfo;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.app.DBPDataSourceRegistry;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.exec.DBCAttributeMetaData;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.exec.DBCStatementType;
import org.jkiss.dbeaver.model.exec.DBCTransactionManager;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAssociation;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;
import org.jkiss.dbeaver.model.struct.DBSEntityAttributeRef;
import org.jkiss.dbeaver.model.struct.DBSEntityConstraint;
import org.jkiss.dbeaver.model.struct.DBSEntityConstraintType;
import org.jkiss.dbeaver.model.struct.DBSEntityReferrer;
import org.jkiss.dbeaver.model.struct.DBSInstance;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import org.jkiss.dbeaver.model.struct.rdb.DBSCatalog;
import org.jkiss.dbeaver.model.struct.rdb.DBSSchema;
import org.jkiss.dbeaver.model.struct.rdb.DBSTable;
import org.jkiss.dbeaver.model.struct.rdb.DBSTableIndex;
import org.jkiss.dbeaver.runtime.DBWorkbench;

/** DatabaseGateway on top of DBeaver's data source registry and execution API. */
public final class DBeaverGateway implements DatabaseGateway {

    private static final Log log = Log.getLog(DBeaverGateway.class);
    private static final int QUERY_TIMEOUT_SECONDS = 60;
    private static final int MAX_CELL_CHARS = 2000;

    /** isolated = auto-commit was disabled on a context owned by this gateway. */
    private record ContextHandle(DBCExecutionContext context, boolean isolated) {
    }

    private final Map<String, ContextHandle> contexts = new ConcurrentHashMap<>();

    @Override
    public List<ConnectionInfo> listConnections() {
        List<ConnectionInfo> out = new ArrayList<>();
        for (DBPProject project : DBWorkbench.getPlatform().getWorkspace().getProjects()) {
            DBPDataSourceRegistry registry = project.getDataSourceRegistry();
            if (registry == null) {
                continue;
            }
            for (DBPDataSourceContainer c : registry.getDataSources()) {
                out.add(new ConnectionInfo(c.getId(), c.getName(),
                        c.getDriver() == null ? "?" : c.getDriver().getName(), project.getName(),
                        c.isConnected(), c.isConnectionReadOnly()));
            }
        }
        return out;
    }

    public DBPDataSourceContainer findContainer(String connectionId) {
        if (connectionId == null) {
            return null;
        }
        for (DBPProject project : DBWorkbench.getPlatform().getWorkspace().getProjects()) {
            DBPDataSourceRegistry registry = project.getDataSourceRegistry();
            DBPDataSourceContainer c = registry == null ? null : registry.getDataSource(connectionId);
            if (c != null) {
                return c;
            }
        }
        return null;
    }

    /** SQL dialect name when connected, otherwise the driver name. Never connects. */
    public String dialectName(String connectionId) {
        DBPDataSourceContainer c = findContainer(connectionId);
        if (c == null) {
            return null;
        }
        DBPDataSource ds = c.getDataSource();
        if (c.isConnected() && ds != null && ds.getSQLDialect() != null) {
            return ds.getSQLDialect().getDialectName();
        }
        return c.getDriver() == null ? null : c.getDriver().getName();
    }

    @Override
    public List<String> listSchemas(String connectionId) throws GatewayException {
        DBRProgressMonitor monitor = new VoidProgressMonitor();
        DBSObjectContainer root = root(connect(container(connectionId)));
        List<String> out = new ArrayList<>();
        try {
            for (DBSObject child : children(root, monitor)) {
                if (child instanceof DBSCatalog) {
                    boolean hasSchemas = false;
                    for (DBSObject sub : children((DBSCatalog) child, monitor)) {
                        if (sub instanceof DBSSchema) {
                            out.add(child.getName() + "." + sub.getName());
                            hasSchemas = true;
                        }
                    }
                    if (!hasSchemas) {
                        out.add(child.getName());
                    }
                } else if (child instanceof DBSSchema) {
                    out.add(child.getName());
                }
            }
        } catch (DBException e) {
            throw new GatewayException("Falha ao ler schemas: " + e.getMessage(), e);
        }
        return out;
    }

    @Override
    public List<TableInfo> listTables(String connectionId, String schema, String nameFilter, int limit)
            throws GatewayException {
        DBRProgressMonitor monitor = new VoidProgressMonitor();
        DBPDataSource ds = connect(container(connectionId));
        String filter = nameFilter == null ? null : nameFilter.toLowerCase(Locale.ROOT);
        List<TableInfo> out = new ArrayList<>();
        try {
            for (DBSObject child : children(resolveContainer(ds, schema, monitor), monitor)) {
                if (!(child instanceof DBSEntity)) {
                    continue;
                }
                if (filter != null && !child.getName().toLowerCase(Locale.ROOT).contains(filter)) {
                    continue;
                }
                out.add(new TableInfo(child.getName(), entityType((DBSEntity) child)));
                if (out.size() >= limit) {
                    break;
                }
            }
        } catch (DBException e) {
            throw new GatewayException("Falha ao listar tabelas: " + e.getMessage(), e);
        }
        return out;
    }

    @Override
    public TableDescription describeTable(String connectionId, String schema, String table) throws GatewayException {
        DBRProgressMonitor monitor = new VoidProgressMonitor();
        DBPDataSource ds = connect(container(connectionId));
        try {
            DBSObjectContainer parent = resolveContainer(ds, schema, monitor);
            DBSObject object = child(parent, table, monitor);
            if (!(object instanceof DBSEntity)) {
                throw new GatewayException("Tabela não encontrada: " + table, null);
            }
            DBSEntity entity = (DBSEntity) object;
            List<ColumnInfo> columns = new ArrayList<>();
            Collection<? extends DBSEntityAttribute> attributes = entity.getAttributes(monitor);
            if (attributes != null) {
                for (DBSEntityAttribute a : attributes) {
                    columns.add(new ColumnInfo(a.getName(), a.getFullTypeName(), !a.isRequired(), a.getDefaultValue()));
                }
            }
            List<String> primaryKey = new ArrayList<>();
            Collection<? extends DBSEntityConstraint> constraints = entity.getConstraints(monitor);
            if (constraints != null) {
                for (DBSEntityConstraint c : constraints) {
                    if (c.getConstraintType() == DBSEntityConstraintType.PRIMARY_KEY && c instanceof DBSEntityReferrer) {
                        primaryKey.addAll(attributeNames((DBSEntityReferrer) c, monitor));
                    }
                }
            }
            List<ForeignKeyInfo> foreignKeys = new ArrayList<>();
            Collection<? extends DBSEntityAssociation> associations = entity.getAssociations(monitor);
            if (associations != null) {
                for (DBSEntityAssociation fk : associations) {
                    DBSEntity ref = fk.getAssociatedEntity();
                    DBSEntityConstraint refConstraint = fk.getReferencedConstraint();
                    foreignKeys.add(new ForeignKeyInfo(fk.getName(),
                            fk instanceof DBSEntityReferrer ? attributeNames((DBSEntityReferrer) fk, monitor) : List.of(),
                            ref == null ? "?" : qualifiedName(ref),
                            refConstraint instanceof DBSEntityReferrer
                                    ? attributeNames((DBSEntityReferrer) refConstraint, monitor) : List.of()));
                }
            }
            List<IndexInfo> indexes = new ArrayList<>();
            if (entity instanceof DBSTable) {
                Collection<? extends DBSTableIndex> tableIndexes = ((DBSTable) entity).getIndexes(monitor);
                if (tableIndexes != null) {
                    for (DBSTableIndex idx : tableIndexes) {
                        indexes.add(new IndexInfo(idx.getName(), idx.isUnique(), attributeNames(idx, monitor)));
                    }
                }
            }
            String schemaName = entity.getParentObject() == null ? schema : entity.getParentObject().getName();
            return new TableDescription(schemaName, entity.getName(), entityType(entity), columns, primaryKey,
                    foreignKeys, indexes);
        } catch (DBException e) {
            throw new GatewayException("Falha ao descrever " + table + ": " + e.getMessage(), e);
        }
    }

    @Override
    public QueryResult runReadQuery(String connectionId, String sql, int maxRows, CancellationToken cancel)
            throws GatewayException {
        ContextHandle handle = context(connectionId);
        DBRProgressMonitor monitor = new VoidProgressMonitor();
        try (DBCSession session = handle.context().openSession(monitor, DBCExecutionPurpose.UTIL, "AI Chat query")) {
            DBCTransactionManager txn = handle.isolated() ? DBUtils.getTransactionManager(handle.context()) : null;
            try (DBCStatement statement = session.prepareStatement(DBCStatementType.QUERY, sql, false, false, false);
                    CancellationToken.Registration registration = cancel.onCancel(() -> cancelQuietly(statement, monitor))) {
                statement.setLimit(0, maxRows + 1L);
                statement.setStatementTimeout(QUERY_TIMEOUT_SECONDS);
                if (!statement.executeStatement()) {
                    return new QueryResult(List.of("resultado"),
                            List.of(List.of("Comando executado sem conjunto de resultados.")), false);
                }
                try (DBCResultSet rs = statement.openResultSet()) {
                    List<String> columns = new ArrayList<>();
                    for (DBCAttributeMetaData attribute : rs.getMeta().getAttributes()) {
                        columns.add(attribute.getLabel());
                    }
                    List<List<String>> rows = new ArrayList<>();
                    boolean truncated = false;
                    while (rs.nextRow()) {
                        if (rows.size() >= maxRows) {
                            truncated = true;
                            break;
                        }
                        List<String> row = new ArrayList<>(columns.size());
                        for (int i = 0; i < columns.size(); i++) {
                            row.add(cell(rs.getAttributeValue(i)));
                        }
                        rows.add(row);
                    }
                    return new QueryResult(columns, rows, truncated);
                }
            } finally {
                if (txn != null) {
                    rollbackQuietly(txn, session);
                }
            }
        } catch (DBException e) {
            if (cancel.isCancelled()) {
                throw new CancelledException();
            }
            throw new GatewayException("Erro ao executar consulta: " + e.getMessage(), e);
        }
    }

    @Override
    public long executeUpdate(String connectionId, String sql, CancellationToken cancel) throws GatewayException {
        ContextHandle handle = context(connectionId);
        DBRProgressMonitor monitor = new VoidProgressMonitor();
        try (DBCSession session = handle.context().openSession(monitor, DBCExecutionPurpose.USER, "AI Chat write")) {
            DBCTransactionManager txn = handle.isolated() ? DBUtils.getTransactionManager(handle.context()) : null;
            try (DBCStatement statement = session.prepareStatement(DBCStatementType.QUERY, sql, false, false, false);
                    CancellationToken.Registration registration = cancel.onCancel(() -> cancelQuietly(statement, monitor))) {
                statement.setStatementTimeout(QUERY_TIMEOUT_SECONDS);
                statement.executeStatement();
                long count = statement.getUpdateRowCount();
                if (txn != null) {
                    txn.commit(session);
                }
                return count;
            } catch (DBException | RuntimeException e) {
                if (txn != null) {
                    rollbackQuietly(txn, session);
                }
                throw e;
            }
        } catch (DBException e) {
            if (cancel.isCancelled()) {
                throw new CancelledException();
            }
            throw new GatewayException("Erro ao executar comando: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<SqlKind> classifyNative(String connectionId, String sql) {
        try {
            return DBeaverCompat.classifyNative(connect(container(connectionId)), sql);
        } catch (GatewayException e) {
            return Optional.empty();
        }
    }

    /** Closes the isolated contexts opened by this gateway. */
    public void close() {
        for (ContextHandle handle : contexts.values()) {
            if (handle.isolated()) {
                try {
                    handle.context().close();
                } catch (RuntimeException e) {
                    log.debug("Error closing AI Chat context", e);
                }
            }
        }
        contexts.clear();
    }

    private DBPDataSourceContainer container(String connectionId) throws GatewayException {
        DBPDataSourceContainer c = findContainer(connectionId);
        if (c == null) {
            throw new GatewayException("Conexão não encontrada: " + connectionId, null);
        }
        return c;
    }

    private static DBPDataSource connect(DBPDataSourceContainer c) throws GatewayException {
        if (!c.isConnected()) {
            try {
                c.connect(new VoidProgressMonitor(), true, true);
            } catch (DBException e) {
                throw new GatewayException("Falha ao conectar em " + c.getName() + ": " + e.getMessage(), e);
            }
        }
        DBPDataSource ds = c.getDataSource();
        if (ds == null) {
            throw new GatewayException("A conexão " + c.getName() + " não está disponível.", null);
        }
        return ds;
    }

    private synchronized ContextHandle context(String connectionId) throws GatewayException {
        DBPDataSource ds = connect(container(connectionId));
        ContextHandle existing = contexts.get(connectionId);
        if (existing != null && existing.context().getDataSource() == ds && existing.context().isConnected()) {
            return existing;
        }
        DBRProgressMonitor monitor = new VoidProgressMonitor();
        DBSInstance instance = ds.getDefaultInstance();
        DBCExecutionContext defaultContext = instance.getDefaultContext(monitor, false);
        ContextHandle handle;
        try {
            DBCExecutionContext isolated = instance.openIsolatedContext(monitor, "AI Chat", defaultContext);
            DBCTransactionManager txn = DBUtils.getTransactionManager(isolated);
            if (txn != null && txn.isAutoCommit()) {
                txn.setAutoCommit(monitor, false);
            }
            handle = new ContextHandle(isolated, txn != null);
        } catch (DBException | RuntimeException e) {
            log.debug("Isolated context not available, using default context", e);
            handle = new ContextHandle(defaultContext, false);
        }
        contexts.put(connectionId, handle);
        return handle;
    }

    private static DBSObjectContainer root(DBPDataSource ds) throws GatewayException {
        if (ds instanceof DBSObjectContainer) {
            return (DBSObjectContainer) ds;
        }
        throw new GatewayException("Esta conexão não expõe objetos navegáveis.", null);
    }

    /** schema null -> default schema/catalog of the default context; "a.b" -> catalog a, schema b. */
    private DBSObjectContainer resolveContainer(DBPDataSource ds, String schema, DBRProgressMonitor monitor)
            throws DBException, GatewayException {
        DBSObjectContainer root = root(ds);
        DBCExecutionContext ctx = ds.getDefaultInstance().getDefaultContext(monitor, true);
        var defaults = ctx == null ? null : ctx.getContextDefaults();
        if (schema == null) {
            if (defaults != null && defaults.getDefaultSchema() instanceof DBSObjectContainer) {
                return (DBSObjectContainer) defaults.getDefaultSchema();
            }
            if (defaults != null && defaults.getDefaultCatalog() instanceof DBSObjectContainer) {
                return (DBSObjectContainer) defaults.getDefaultCatalog();
            }
            return root;
        }
        String[] parts = schema.split("\\.", 2);
        DBSObject first = child(root, parts[0], monitor);
        if (first == null && parts.length == 1 && defaults != null
                && defaults.getDefaultCatalog() instanceof DBSObjectContainer) {
            first = child((DBSObjectContainer) defaults.getDefaultCatalog(), parts[0], monitor);
        }
        DBSObject target = first;
        if (parts.length == 2 && first instanceof DBSObjectContainer) {
            target = child((DBSObjectContainer) first, parts[1], monitor);
        }
        if (!(target instanceof DBSObjectContainer)) {
            throw new GatewayException("Schema não encontrado: " + schema + ". Use list_schemas.", null);
        }
        return (DBSObjectContainer) target;
    }

    private static Collection<? extends DBSObject> children(DBSObjectContainer container, DBRProgressMonitor monitor)
            throws DBException {
        Collection<? extends DBSObject> children = container.getChildren(monitor);
        return children == null ? List.of() : children;
    }

    private static DBSObject child(DBSObjectContainer container, String name, DBRProgressMonitor monitor)
            throws DBException {
        DBSObject exact = container.getChild(monitor, name);
        if (exact != null) {
            return exact;
        }
        for (DBSObject o : children(container, monitor)) {
            if (o.getName().equalsIgnoreCase(name)) {
                return o;
            }
        }
        return null;
    }

    private static List<String> attributeNames(DBSEntityReferrer referrer, DBRProgressMonitor monitor)
            throws DBException {
        List<String> names = new ArrayList<>();
        var refs = referrer.getAttributeReferences(monitor);
        if (refs != null) {
            for (DBSEntityAttributeRef ref : refs) {
                if (ref.getAttribute() != null) {
                    names.add(ref.getAttribute().getName());
                }
            }
        }
        return names;
    }

    private static String entityType(DBSEntity entity) {
        if (entity instanceof DBSTable && ((DBSTable) entity).isView()) {
            return "VIEW";
        }
        return entity.getEntityType() == null ? "TABLE" : entity.getEntityType().getName().toUpperCase(Locale.ROOT);
    }

    private static String qualifiedName(DBSEntity entity) {
        DBSObject parent = entity.getParentObject();
        return parent == null || parent instanceof DBPDataSource ? entity.getName() : parent.getName() + "." + entity.getName();
    }

    private static String cell(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[]) {
            return "<binário " + ((byte[]) value).length + " bytes>";
        }
        String s = String.valueOf(value);
        return s.length() > MAX_CELL_CHARS ? s.substring(0, MAX_CELL_CHARS) + "…" : s;
    }

    private static void cancelQuietly(DBCStatement statement, DBRProgressMonitor monitor) {
        try {
            statement.cancelBlock(monitor, null);
        } catch (DBException | RuntimeException e) {
            log.debug("Cancel failed", e);
        }
    }

    private static void rollbackQuietly(DBCTransactionManager txn, DBCSession session) {
        try {
            txn.rollback(session, null);
        } catch (DBException | RuntimeException e) {
            log.debug("Rollback failed", e);
        }
    }
}
```

- [ ] **Step 4: Compilar**

```bash
. scripts/env.sh
mvn clean verify 2>&1 | grep -E 'ERROR|BUILD|Tests run:' | tail -20
```

Expected: `Tests run: 112, Failures: 0` e `BUILD SUCCESS`.
Se um tipo genérico não bater (ex.: `getAttributeReferences` ou `getIndexes` devolvendo outro tipo), troque a variável por `var` e mantenha o laço; se um método faltar na 24.1, a chamada vai para `DBeaverCompat` com fallback. Se `DBCSession`/`DBCStatement`/`DBCResultSet` não aceitarem try-with-resources, chame `close()` em `finally`.

- [ ] **Step 5: Commit**

```bash
git add bundles
git commit -m "feat(bundle): DBeaver gateway with isolated contexts, compat layer and editor context" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: Configurações (provedores, chave segura, limites) + página de preferências

**Files:**
- Modify: `bundles/io.github.eduardo.dbeaver.aichat/src/io/github/eduardo/dbeaver/aichat/Activator.java` (adicionar `conversationsDir()`), `bundles/io.github.eduardo.dbeaver.aichat/plugin.xml`
- Create: `B/settings/ProviderSettings.java`, `B/settings/ProviderPresets.java`, `B/settings/ProviderDialog.java`, `B/settings/AiChatPreferencePage.java`

**Interfaces:**
- Consumes: `ProviderConfig`, `ProviderType`, `ProviderFactory`, `ChatRequest`, `ChatMessage`, `AssistantTurn`, `LlmException` (Tasks 2–4), `ToolLimits` (Task 6), `SessionSettings` (Task 7), `Json.GSON`.
- Produces:
  - `Activator.conversationsDir()` → `java.nio.file.Path`
  - `ProviderSettings.loadProviders()`, `saveProviders(List<ProviderConfig>)`, `activeProviderId()`, `setActiveProviderId(String)`, `apiKey(String id)` (nullable), `setApiKey(String id, String key)`, `removeApiKey(String id)`, `toolLimits()`, `sessionSettings()`, `saveLimits(ToolLimits, int maxIterations)`
  - `AiChatPreferencePage.ID = "io.github.eduardo.dbeaver.aichat.preferences"`

- [ ] **Step 1: Adicionar `conversationsDir()` ao Activator**

Em `bundles/io.github.eduardo.dbeaver.aichat/src/io/github/eduardo/dbeaver/aichat/Activator.java`, adicionar o método:

```java
    /** {workspace}/.metadata/.plugins/io.github.eduardo.dbeaver.aichat/conversations */
    public java.nio.file.Path conversationsDir() {
        return getStateLocation().append("conversations").toFile().toPath();
    }
```

- [ ] **Step 2: Implementar `ProviderSettings`**

`B/settings/ProviderSettings.java`:

```java
package io.github.eduardo.dbeaver.aichat.settings;

import com.google.gson.reflect.TypeToken;
import io.github.eduardo.dbeaver.aichat.Activator;
import io.github.eduardo.dbeaver.aichat.core.Json;
import io.github.eduardo.dbeaver.aichat.core.agent.SessionSettings;
import io.github.eduardo.dbeaver.aichat.core.llm.ProviderConfig;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolLimits;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;
import org.jkiss.dbeaver.Log;
import org.osgi.service.prefs.BackingStoreException;

/** Provider list and limits in instance preferences; API keys only in Eclipse secure storage. */
public final class ProviderSettings {

    private static final Log log = Log.getLog(ProviderSettings.class);

    private static final String KEY_PROVIDERS = "providers";
    private static final String KEY_ACTIVE = "activeProvider";
    private static final String KEY_MAX_ROWS = "maxRows";
    private static final String KEY_MAX_CHARS = "maxResultChars";
    private static final String KEY_MAX_ITERATIONS = "maxIterations";
    private static final String SECURE_NODE = Activator.PLUGIN_ID + "/apiKeys";

    private ProviderSettings() {
    }

    private static IEclipsePreferences prefs() {
        return InstanceScope.INSTANCE.getNode(Activator.PLUGIN_ID);
    }

    public static List<ProviderConfig> loadProviders() {
        String json = prefs().get(KEY_PROVIDERS, "[]");
        try {
            List<ProviderConfig> list = Json.GSON.fromJson(json, new TypeToken<List<ProviderConfig>>() {
            }.getType());
            return list == null ? new ArrayList<>() : new ArrayList<>(list);
        } catch (RuntimeException e) {
            log.error("Invalid AI Chat provider settings", e);
            return new ArrayList<>();
        }
    }

    public static void saveProviders(List<ProviderConfig> providers) throws BackingStoreException {
        prefs().put(KEY_PROVIDERS, Json.GSON.toJson(providers));
        prefs().flush();
    }

    public static String activeProviderId() {
        return prefs().get(KEY_ACTIVE, null);
    }

    public static void setActiveProviderId(String id) {
        if (id == null) {
            prefs().remove(KEY_ACTIVE);
        } else {
            prefs().put(KEY_ACTIVE, id);
        }
        try {
            prefs().flush();
        } catch (BackingStoreException e) {
            log.error("Could not save active provider", e);
        }
    }

    public static String apiKey(String providerId) {
        try {
            return secureNode().get(providerId, null);
        } catch (StorageException e) {
            log.error("Could not read API key from secure storage", e);
            return null;
        }
    }

    public static void setApiKey(String providerId, String key) throws StorageException, IOException {
        ISecurePreferences node = secureNode();
        if (key == null || key.isBlank()) {
            node.remove(providerId);
        } else {
            node.put(providerId, key.trim(), true);
        }
        node.flush();
    }

    public static void removeApiKey(String providerId) {
        try {
            ISecurePreferences node = secureNode();
            node.remove(providerId);
            node.flush();
        } catch (IOException e) {
            log.error("Could not remove API key", e);
        }
    }

    public static ToolLimits toolLimits() {
        IEclipsePreferences p = prefs();
        return new ToolLimits(
                p.getInt(KEY_MAX_ROWS, ToolLimits.DEFAULT.maxRows()),
                p.getInt(KEY_MAX_CHARS, ToolLimits.DEFAULT.maxResultChars()),
                ToolLimits.DEFAULT.maxListItems());
    }

    public static SessionSettings sessionSettings() {
        return new SessionSettings(prefs().getInt(KEY_MAX_ITERATIONS, SessionSettings.DEFAULT.maxIterations()));
    }

    public static void saveLimits(ToolLimits limits, int maxIterations) throws BackingStoreException {
        IEclipsePreferences p = prefs();
        p.putInt(KEY_MAX_ROWS, limits.maxRows());
        p.putInt(KEY_MAX_CHARS, limits.maxResultChars());
        p.putInt(KEY_MAX_ITERATIONS, maxIterations);
        p.flush();
    }

    private static ISecurePreferences secureNode() {
        return SecurePreferencesFactory.getDefault().node(SECURE_NODE);
    }
}
```

- [ ] **Step 3: Implementar `ProviderPresets`**

`B/settings/ProviderPresets.java`:

```java
package io.github.eduardo.dbeaver.aichat.settings;

import io.github.eduardo.dbeaver.aichat.core.llm.AnthropicProvider;
import io.github.eduardo.dbeaver.aichat.core.llm.ProviderType;
import java.util.List;

/** Starting values offered when adding a provider. Empty model = the user must type one. */
final class ProviderPresets {

    record Preset(String label, ProviderType type, String baseUrl, String model, boolean serverFallback) {
    }

    static final List<Preset> ALL = List.of(
            new Preset("Anthropic (Claude)", ProviderType.ANTHROPIC, AnthropicProvider.DEFAULT_BASE_URL, "claude-opus-5-5", true),
            new Preset("OpenAI", ProviderType.OPENAI_COMPATIBLE, "https://api.openai.com/v1", "", false),
            new Preset("Ollama (local)", ProviderType.OPENAI_COMPATIBLE, "http://localhost:11434/v1", "", false),
            new Preset("LM Studio (local)", ProviderType.OPENAI_COMPATIBLE, "http://localhost:1234/v1", "", false),
            new Preset("OpenRouter", ProviderType.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", "", false),
            new Preset("Personalizado (OpenAI-compatible)", ProviderType.OPENAI_COMPATIBLE, "", "", false));

    private ProviderPresets() {
    }
}
```

- [ ] **Step 4: Implementar `ProviderDialog`**

`B/settings/ProviderDialog.java`:

```java
package io.github.eduardo.dbeaver.aichat.settings;

import io.github.eduardo.dbeaver.aichat.core.llm.ProviderConfig;
import io.github.eduardo.dbeaver.aichat.core.llm.ProviderType;
import java.util.UUID;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;

/** Add/edit one provider. apiKey() is null when editing and the key field was left empty (keep current). */
final class ProviderDialog extends Dialog {

    private final ProviderConfig initial;
    private ProviderConfig result;
    private String apiKey;

    private Combo presetCombo;
    private Text nameText;
    private Combo typeCombo;
    private Text urlText;
    private Text modelText;
    private Text keyText;
    private Spinner maxTokensSpinner;
    private Text temperatureText;
    private Button fallbackCheck;

    ProviderDialog(Shell shell, ProviderConfig initial) {
        super(shell);
        this.initial = initial;
    }

    ProviderConfig result() {
        return result;
    }

    String apiKey() {
        return apiKey;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(initial == null ? "Adicionar provedor de IA" : "Editar provedor de IA");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        Composite c = new Composite(area, SWT.NONE);
        c.setLayout(new GridLayout(2, false));
        c.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        if (initial == null) {
            label(c, "Modelo pronto:");
            presetCombo = new Combo(c, SWT.DROP_DOWN | SWT.READ_ONLY);
            ProviderPresets.ALL.forEach(p -> presetCombo.add(p.label()));
            presetCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            presetCombo.addListener(SWT.Selection, e -> applyPreset(ProviderPresets.ALL.get(presetCombo.getSelectionIndex())));
        }
        nameText = text(c, "Nome:", SWT.BORDER);
        label(c, "Tipo:");
        typeCombo = new Combo(c, SWT.DROP_DOWN | SWT.READ_ONLY);
        typeCombo.add("OpenAI-compatible");
        typeCombo.add("Anthropic");
        typeCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        typeCombo.addListener(SWT.Selection, e -> updateEnablement());
        urlText = text(c, "URL base:", SWT.BORDER);
        modelText = text(c, "Modelo:", SWT.BORDER);
        keyText = text(c, "Chave de API:", SWT.BORDER | SWT.PASSWORD);
        if (initial != null) {
            keyText.setMessage("(vazio = manter a chave atual)");
        }
        label(c, "Máx. tokens (0 = padrão):");
        maxTokensSpinner = new Spinner(c, SWT.BORDER);
        maxTokensSpinner.setMinimum(0);
        maxTokensSpinner.setMaximum(1_000_000);
        maxTokensSpinner.setIncrement(1000);
        temperatureText = text(c, "Temperatura (vazio = padrão):", SWT.BORDER);
        new Label(c, SWT.NONE);
        fallbackCheck = new Button(c, SWT.CHECK);
        fallbackCheck.setText("Fallback no servidor em recusas (Anthropic)");

        if (initial != null) {
            nameText.setText(initial.name());
            typeCombo.select(initial.type() == ProviderType.ANTHROPIC ? 1 : 0);
            urlText.setText(nullToEmpty(initial.baseUrl()));
            modelText.setText(nullToEmpty(initial.model()));
            maxTokensSpinner.setSelection(Math.max(0, initial.maxTokens()));
            temperatureText.setText(initial.temperature() == null ? "" : String.valueOf(initial.temperature()));
            fallbackCheck.setSelection(initial.serverFallback());
        } else {
            presetCombo.select(0);
            applyPreset(ProviderPresets.ALL.get(0));
        }
        updateEnablement();
        return area;
    }

    private void applyPreset(ProviderPresets.Preset preset) {
        nameText.setText(preset.label());
        typeCombo.select(preset.type() == ProviderType.ANTHROPIC ? 1 : 0);
        urlText.setText(preset.baseUrl());
        modelText.setText(preset.model());
        fallbackCheck.setSelection(preset.serverFallback());
        updateEnablement();
    }

    private void updateEnablement() {
        boolean anthropic = typeCombo.getSelectionIndex() == 1;
        temperatureText.setEnabled(!anthropic);
        fallbackCheck.setEnabled(anthropic);
    }

    @Override
    protected void okPressed() {
        String name = nameText.getText().trim();
        String url = urlText.getText().trim();
        String model = modelText.getText().trim();
        if (name.isEmpty() || url.isEmpty() || model.isEmpty()) {
            MessageDialog.openError(getShell(), "AI Chat", "Preencha nome, URL base e modelo.");
            return;
        }
        boolean anthropic = typeCombo.getSelectionIndex() == 1;
        Double temperature = null;
        String t = temperatureText.getText().trim().replace(',', '.');
        if (!anthropic && !t.isEmpty()) {
            try {
                temperature = Double.parseDouble(t);
            } catch (NumberFormatException e) {
                MessageDialog.openError(getShell(), "AI Chat", "Temperatura inválida: " + t);
                return;
            }
        }
        result = new ProviderConfig(initial != null ? initial.id() : UUID.randomUUID().toString(), name,
                anthropic ? ProviderType.ANTHROPIC : ProviderType.OPENAI_COMPATIBLE, url, model,
                maxTokensSpinner.getSelection(), temperature, anthropic && fallbackCheck.getSelection());
        String key = keyText.getText().trim();
        apiKey = initial != null && key.isEmpty() ? null : key;
        super.okPressed();
    }

    private static void label(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
    }

    private static Text text(Composite parent, String label, int style) {
        label(parent, label);
        Text t = new Text(parent, style);
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.widthHint = 320;
        t.setLayoutData(gd);
        return t;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
```

- [ ] **Step 5: Implementar `AiChatPreferencePage`**

`B/settings/AiChatPreferencePage.java`:

```java
package io.github.eduardo.dbeaver.aichat.settings;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.agent.SessionSettings;
import io.github.eduardo.dbeaver.aichat.core.llm.AssistantTurn;
import io.github.eduardo.dbeaver.aichat.core.llm.ChatMessage;
import io.github.eduardo.dbeaver.aichat.core.llm.ChatRequest;
import io.github.eduardo.dbeaver.aichat.core.llm.LlmException;
import io.github.eduardo.dbeaver.aichat.core.llm.ProviderConfig;
import io.github.eduardo.dbeaver.aichat.core.llm.ProviderFactory;
import io.github.eduardo.dbeaver.aichat.core.llm.ProviderType;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolLimits;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Table;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

public class AiChatPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    public static final String ID = "io.github.eduardo.dbeaver.aichat.preferences";

    private final List<ProviderConfig> providers = new ArrayList<>();
    private final Map<String, String> pendingKeys = new HashMap<>();
    private final Set<String> removedIds = new HashSet<>();
    private String activeId;

    private TableViewer viewer;
    private Spinner maxRows;
    private Spinner maxChars;
    private Spinner maxIterations;

    @Override
    public void init(IWorkbench workbench) {
        providers.addAll(ProviderSettings.loadProviders());
        activeId = ProviderSettings.activeProviderId();
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(2, false));

        Label title = new Label(root, SWT.NONE);
        title.setText("Provedores de IA (★ = padrão):");
        title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        viewer = new TableViewer(root, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
        Table table = viewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true);
        tableData.heightHint = 160;
        table.setLayoutData(tableData);
        column("Nome", 160, p -> (p.id().equals(activeId) ? "★ " : "") + p.name());
        column("Tipo", 120, p -> p.type() == ProviderType.ANTHROPIC ? "Anthropic" : "OpenAI-compatible");
        column("Modelo", 160, ProviderConfig::model);
        column("URL base", 220, ProviderConfig::baseUrl);
        viewer.setContentProvider(ArrayContentProvider.getInstance());
        viewer.setInput(providers);
        viewer.addDoubleClickListener(e -> editProvider());

        Composite buttons = new Composite(root, SWT.NONE);
        buttons.setLayout(new GridLayout(1, true));
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));
        button(buttons, "Adicionar…", this::addProvider);
        button(buttons, "Editar…", this::editProvider);
        button(buttons, "Remover", this::removeProvider);
        button(buttons, "Usar como padrão", this::makeDefault);
        button(buttons, "Testar", this::testProvider);

        Group limits = new Group(root, SWT.NONE);
        limits.setText("Limites");
        limits.setLayout(new GridLayout(2, false));
        limits.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        ToolLimits toolLimits = ProviderSettings.toolLimits();
        maxRows = spinner(limits, "Máximo de linhas por consulta:", 1, 5000, toolLimits.maxRows());
        maxChars = spinner(limits, "Máximo de caracteres por resultado:", 1000, 200_000, toolLimits.maxResultChars());
        maxIterations = spinner(limits, "Máximo de chamadas ao modelo por pergunta:", 1, 50,
                ProviderSettings.sessionSettings().maxIterations());

        Label warning = new Label(root, SWT.WRAP);
        warning.setText("Atenção: nomes de tabelas, estruturas e resultados de consultas (até os limites acima) "
                + "são enviados ao provedor de IA configurado. Comandos de escrita sempre exigem sua aprovação no chat.");
        GridData warningData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        warningData.widthHint = 520;
        warning.setLayoutData(warningData);
        return root;
    }

    private void column(String title, int width, Function<ProviderConfig, String> text) {
        TableViewerColumn column = new TableViewerColumn(viewer, SWT.NONE);
        column.getColumn().setText(title);
        column.getColumn().setWidth(width);
        column.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return text.apply((ProviderConfig) element);
            }
        });
    }

    private static void button(Composite parent, String text, Runnable action) {
        Button b = new Button(parent, SWT.PUSH);
        b.setText(text);
        b.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        b.addListener(SWT.Selection, e -> action.run());
    }

    private static Spinner spinner(Composite parent, String label, int min, int max, int value) {
        new Label(parent, SWT.NONE).setText(label);
        Spinner s = new Spinner(parent, SWT.BORDER);
        s.setMinimum(min);
        s.setMaximum(max);
        s.setSelection(value);
        return s;
    }

    private ProviderConfig selected() {
        Object o = ((IStructuredSelection) viewer.getSelection()).getFirstElement();
        return o instanceof ProviderConfig ? (ProviderConfig) o : null;
    }

    private void addProvider() {
        ProviderDialog dialog = new ProviderDialog(getShell(), null);
        if (dialog.open() != Window.OK) {
            return;
        }
        ProviderConfig config = dialog.result();
        providers.add(config);
        pendingKeys.put(config.id(), dialog.apiKey());
        if (activeId == null) {
            activeId = config.id();
        }
        viewer.refresh();
    }

    private void editProvider() {
        ProviderConfig current = selected();
        if (current == null) {
            return;
        }
        ProviderDialog dialog = new ProviderDialog(getShell(), current);
        if (dialog.open() != Window.OK) {
            return;
        }
        providers.set(providers.indexOf(current), dialog.result());
        if (dialog.apiKey() != null) {
            pendingKeys.put(current.id(), dialog.apiKey());
        }
        viewer.refresh();
    }

    private void removeProvider() {
        ProviderConfig current = selected();
        if (current == null) {
            return;
        }
        providers.remove(current);
        pendingKeys.remove(current.id());
        removedIds.add(current.id());
        if (current.id().equals(activeId)) {
            activeId = providers.isEmpty() ? null : providers.get(0).id();
        }
        viewer.refresh();
    }

    private void makeDefault() {
        ProviderConfig current = selected();
        if (current != null) {
            activeId = current.id();
            viewer.refresh();
        }
    }

    private void testProvider() {
        ProviderConfig config = selected();
        if (config == null) {
            return;
        }
        String key = pendingKeys.containsKey(config.id()) ? pendingKeys.get(config.id()) : ProviderSettings.apiKey(config.id());
        String[] reply = new String[1];
        try {
            new ProgressMonitorDialog(getShell()).run(true, false, (IProgressMonitor monitor) -> {
                monitor.beginTask("Testando " + config.name() + "…", IProgressMonitor.UNKNOWN);
                try {
                    AssistantTurn turn = ProviderFactory.create(config, key, ProviderFactory.defaultHttpClient()).stream(
                            new ChatRequest(config.model(), "Responda apenas com a palavra OK.",
                                    List.of(ChatMessage.user("Teste de conexão")), List.of(), 1024, null),
                            t -> { }, new CancellationToken());
                    reply[0] = turn.text();
                } catch (LlmException e) {
                    throw new InvocationTargetException(e);
                } finally {
                    monitor.done();
                }
            });
            String text = reply[0] == null ? "" : reply[0];
            MessageDialog.openInformation(getShell(), "AI Chat",
                    "Conexão OK. Resposta: " + (text.length() > 200 ? text.substring(0, 200) + "…" : text));
        } catch (InvocationTargetException e) {
            MessageDialog.openError(getShell(), "AI Chat", "Falha: " + e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    protected void performDefaults() {
        maxRows.setSelection(ToolLimits.DEFAULT.maxRows());
        maxChars.setSelection(ToolLimits.DEFAULT.maxResultChars());
        maxIterations.setSelection(SessionSettings.DEFAULT.maxIterations());
        super.performDefaults();
    }

    @Override
    public boolean performOk() {
        try {
            ProviderSettings.saveProviders(providers);
            ProviderSettings.setActiveProviderId(activeId);
            for (String id : removedIds) {
                ProviderSettings.removeApiKey(id);
            }
            for (Map.Entry<String, String> e : pendingKeys.entrySet()) {
                ProviderSettings.setApiKey(e.getKey(), e.getValue());
            }
            ProviderSettings.saveLimits(new ToolLimits(maxRows.getSelection(), maxChars.getSelection(),
                    ToolLimits.DEFAULT.maxListItems()), maxIterations.getSelection());
            pendingKeys.clear();
            removedIds.clear();
            return true;
        } catch (Exception e) {
            MessageDialog.openError(getShell(), "AI Chat", "Não foi possível salvar: " + e.getMessage());
            return false;
        }
    }
}
```

- [ ] **Step 6: Registrar a página em `plugin.xml`**

Substituir o conteúdo de `bundles/io.github.eduardo.dbeaver.aichat/plugin.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<?eclipse version="3.4"?>
<plugin>
   <extension point="org.eclipse.ui.preferencePages">
      <page
            id="io.github.eduardo.dbeaver.aichat.preferences"
            name="AI Chat"
            class="io.github.eduardo.dbeaver.aichat.settings.AiChatPreferencePage"/>
   </extension>
</plugin>
```

- [ ] **Step 7: Compilar**

Run: `mvn clean verify 2>&1 | grep -E 'ERROR|BUILD' | tail -20`
Expected: `BUILD SUCCESS`.

- [ ] **Step 8: Commit**

```bash
git add bundles
git commit -m "feat(bundle): provider settings with secure API keys and preference page" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: View "AI Chat" (UI SWT + Browser, streaming, aprovação, histórico)

**Files:**
- Create: `bundles/io.github.eduardo.dbeaver.aichat/resources/chat/chat.html`, `.../chat.css`, `.../chat.js`
- Create: `B/ui/ChatBrowserBridge.java`, `B/ui/UiApprovalGate.java`, `B/ui/ChatView.java`
- Modify: `bundles/io.github.eduardo.dbeaver.aichat/plugin.xml` (view), `bundles/io.github.eduardo.dbeaver.aichat/build.properties` (incluir `resources/`)

**Interfaces:**
- Consumes: tudo do core; `DBeaverGateway`, `DBeaverCompat`, `EditorContextResolver`, `EditorContext` (Task 10); `ProviderSettings`, `AiChatPreferencePage.ID` (Task 11); `Activator.conversationsDir()`.
- Produces:
  - `ChatView.ID = "io.github.eduardo.dbeaver.aichat.view"`; `ChatView.focusWithEditorContext()` (usado pela Task 13)
  - API JS `window.ChatUI.{clear, addUser, startAssistant, setAssistantHtml, finishAssistant, addTool, setToolResult, addApproval, closeApproval, addNotice, addError}`; funções Java expostas ao JS: `aichatApprove(id)`, `aichatReject(id)`, `aichatInsertSql(sql)`, `aichatOpenSql(sql)`, `aichatCopy(text)`

Decisões de UI:
- O markdown é renderizado no Java (`MarkdownRenderer`, HTML escapado) e enviado ao JS como literal JSON (`Json.GSON.toJson`), nunca concatenado cru.
- CSP da página: `default-src 'none'`; imagens só `data:`; nenhuma requisição de rede sai do chat (bloqueia exfiltração por imagem markdown).
- Navegação para fora de `about:` é bloqueada (`LocationListener`).
- No Windows usa `SWT.EDGE` (WebView2, já distribuído com o DBeaver); se falhar, cai no navegador padrão do SWT.
- Deltas de texto são agrupados e re-renderizados a cada 50 ms.
- IDs de DOM são gerados pela view (`m<n>` mensagens, `t<n>` tools); ids de tool call do provedor podem se repetir (ex.: `call_0` no Ollama).
- Callbacks JS → Java são repassados com `asyncExec`, para não abrir diálogos dentro do callback do navegador.

- [ ] **Step 1: Criar a página do chat**

`resources/chat/chat.html`:

```html
<!DOCTYPE html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline' 'unsafe-eval'; img-src data:">
<style>/*CSS*/</style>
</head>
<body>
<div id="messages"></div>
<script>/*JS*/</script>
</body>
</html>
```

`resources/chat/chat.css`:

```css
body { font-family: "Segoe UI", system-ui, sans-serif; font-size: 13px; margin: 0; padding: 8px; color: #1f2328; background: #ffffff; }
.msg { margin: 8px 0; padding: 8px 10px; border-radius: 6px; line-height: 1.45; overflow-wrap: anywhere; }
.msg.user { background: #e8f0fe; white-space: pre-wrap; }
.msg.assistant { background: #f6f8fa; }
.msg.assistant p { margin: 4px 0; }
pre { background: #eef1f4; padding: 8px; border-radius: 4px; overflow-x: auto; font-family: Consolas, "Courier New", monospace; font-size: 12px; white-space: pre; }
code { font-family: Consolas, "Courier New", monospace; }
table { border-collapse: collapse; margin: 4px 0; }
th, td { border: 1px solid #d0d7de; padding: 2px 6px; }
.code-actions { margin: -4px 0 8px; }
.code-actions button, .approval button { margin-right: 6px; font-size: 12px; cursor: pointer; }
details.tool { margin: 6px 0; border: 1px solid #d0d7de; border-radius: 6px; padding: 4px 8px; font-size: 12px; }
details.tool summary { cursor: pointer; color: #57606a; }
details.tool.error summary { color: #cf222e; }
.approval { border: 2px solid #bf8700; background: #fff8c5; border-radius: 6px; padding: 8px; margin: 8px 0; }
.approval .title { font-weight: 600; }
.approval .reason { margin: 4px 0; font-style: italic; }
.decision { font-weight: 600; margin-top: 4px; }
.notice { color: #57606a; font-style: italic; margin: 6px 0; }
.error-msg { color: #cf222e; background: #ffebe9; border-radius: 6px; padding: 6px 8px; margin: 6px 0; white-space: pre-wrap; }
@media (prefers-color-scheme: dark) {
  body { color: #e6edf3; background: #1e1f22; }
  .msg.user { background: #1f3354; }
  .msg.assistant, pre { background: #2b2d30; }
  th, td, details.tool { border-color: #444c56; }
  .approval { background: #3d2e00; }
  .error-msg { background: #4a1d1d; }
}
```

`resources/chat/chat.js`:

```js
(function () {
  'use strict';
  var list = document.getElementById('messages');

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) { e.className = cls; }
    if (text !== undefined && text !== null) { e.textContent = text; }
    return e;
  }

  function button(label, onClick) {
    var b = el('button', null, label);
    b.addEventListener('click', onClick);
    return b;
  }

  function byId(id) { return document.getElementById(id); }

  function scroll() { window.scrollTo(0, document.body.scrollHeight); }

  function addCodeActions(code) {
    var pre = code.parentNode;
    if (pre.getAttribute('data-actions')) { return; }
    pre.setAttribute('data-actions', '1');
    var bar = el('div', 'code-actions');
    bar.appendChild(button('Inserir no editor', function () { aichatInsertSql(code.textContent); }));
    bar.appendChild(button('Abrir em novo editor', function () { aichatOpenSql(code.textContent); }));
    bar.appendChild(button('Copiar', function () { aichatCopy(code.textContent); }));
    pre.parentNode.insertBefore(bar, pre.nextSibling);
  }

  function settle(box, label) {
    var actions = box.querySelector('.actions');
    if (actions) { actions.parentNode.replaceChild(el('div', 'decision', label), actions); }
  }

  window.ChatUI = {
    clear: function () { list.innerHTML = ''; },
    addUser: function (text) { list.appendChild(el('div', 'msg user', text)); scroll(); },
    startAssistant: function (id) {
      var d = el('div', 'msg assistant');
      d.id = id;
      list.appendChild(d);
      scroll();
    },
    setAssistantHtml: function (id, html) {
      var d = byId(id);
      if (!d) { return; }
      d.innerHTML = html;
      var blocks = d.querySelectorAll('pre > code');
      for (var i = 0; i < blocks.length; i++) { addCodeActions(blocks[i]); }
      scroll();
    },
    finishAssistant: function (id) {
      var d = byId(id);
      if (d && !d.textContent.trim()) { d.parentNode.removeChild(d); }
    },
    addTool: function (id, name, args) {
      var card = el('details', 'tool');
      card.id = id;
      card.appendChild(el('summary', null, 'Ferramenta: ' + name));
      card.appendChild(el('pre', 'args', args));
      card.appendChild(el('pre', 'result', 'executando…'));
      list.appendChild(card);
      scroll();
    },
    setToolResult: function (id, text, isError) {
      var card = byId(id);
      if (!card) { return; }
      card.querySelector('.result').textContent = text;
      if (isError) { card.className += ' error'; }
    },
    addApproval: function (id, connection, sql, reason) {
      var box = el('div', 'approval');
      box.id = 'approval-' + id;
      box.appendChild(el('div', 'title', 'A IA quer executar um comando de escrita na conexão ' + connection + ':'));
      if (reason) { box.appendChild(el('div', 'reason', reason)); }
      box.appendChild(el('pre', null, sql));
      var actions = el('div', 'actions');
      actions.appendChild(button('Aprovar e executar', function () { settle(box, 'Aprovado'); aichatApprove(id); }));
      actions.appendChild(button('Rejeitar', function () { settle(box, 'Rejeitado'); aichatReject(id); }));
      box.appendChild(actions);
      list.appendChild(box);
      scroll();
    },
    closeApproval: function (id, label) {
      var box = byId('approval-' + id);
      if (box) { settle(box, label); }
    },
    addNotice: function (text) { list.appendChild(el('div', 'notice', text)); scroll(); },
    addError: function (text) { list.appendChild(el('div', 'error-msg', text)); scroll(); }
  };
}());
```

- [ ] **Step 2: Implementar `ChatBrowserBridge`**

`B/ui/ChatBrowserBridge.java`:

```java
package io.github.eduardo.dbeaver.aichat.ui;

import io.github.eduardo.dbeaver.aichat.core.Json;
import io.github.eduardo.dbeaver.aichat.core.render.MarkdownRenderer;
import io.github.eduardo.dbeaver.aichat.core.tools.ApprovalRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.browser.LocationListener;
import org.eclipse.swt.browser.ProgressListener;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.jkiss.dbeaver.Log;

/** Owns the Browser widget: Java -> JS calls (queued until the page loads) and JS -> Java callbacks. */
final class ChatBrowserBridge {

    interface Callbacks {
        void approve(String id);

        void reject(String id);

        void insertSql(String sql);

        void openSql(String sql);

        void copy(String text);
    }

    private static final Log log = Log.getLog(ChatBrowserBridge.class);

    private final Browser browser;
    private final MarkdownRenderer markdown = new MarkdownRenderer();
    private final List<String> pending = new ArrayList<>();
    private boolean ready;

    ChatBrowserBridge(Composite parent, Callbacks callbacks) {
        browser = createBrowser(parent);
        function("aichatApprove", callbacks::approve);
        function("aichatReject", callbacks::reject);
        function("aichatInsertSql", callbacks::insertSql);
        function("aichatOpenSql", callbacks::openSql);
        function("aichatCopy", callbacks::copy);
        browser.addProgressListener(ProgressListener.completedAdapter(e -> {
            ready = true;
            for (String script : pending) {
                browser.execute(script);
            }
            pending.clear();
        }));
        browser.addLocationListener(LocationListener.changingAdapter(e -> {
            if (e.location != null && !e.location.startsWith("about:")) {
                e.doit = false;
            }
        }));
        browser.setText(loadPage());
    }

    Control control() {
        return browser;
    }

    boolean isDisposed() {
        return browser.isDisposed();
    }

    void clear() {
        call("clear");
    }

    void addUser(String text) {
        call("addUser", text);
    }

    void startAssistant(String id) {
        call("startAssistant", id);
    }

    void setAssistantMarkdown(String id, String text) {
        call("setAssistantHtml", id, markdown.toHtml(text));
    }

    void finishAssistant(String id) {
        call("finishAssistant", id);
    }

    void addTool(String id, String name, String argumentsJson) {
        call("addTool", id, name, argumentsJson);
    }

    void setToolResult(String id, String text, boolean error) {
        call("setToolResult", id, text, error);
    }

    void addApproval(String id, ApprovalRequest request) {
        call("addApproval", id, request.connectionName(), request.sql(), request.reason() == null ? "" : request.reason());
    }

    void closeApproval(String id, String label) {
        call("closeApproval", id, label);
    }

    void addNotice(String text) {
        call("addNotice", text);
    }

    void addError(String text) {
        call("addError", text);
    }

    private void call(String function, Object... args) {
        if (browser.isDisposed()) {
            return;
        }
        StringBuilder sb = new StringBuilder("ChatUI.").append(function).append('(');
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Json.GSON.toJson(args[i]));
        }
        String script = sb.append(");").toString();
        if (!ready) {
            pending.add(script);
        } else if (!browser.execute(script)) {
            log.debug("Chat script failed: " + function);
        }
    }

    private void function(String name, Consumer<String> handler) {
        new BrowserFunction(browser, name) {
            @Override
            public Object function(Object[] arguments) {
                String arg = arguments.length > 0 && arguments[0] != null ? arguments[0].toString() : "";
                getBrowser().getDisplay().asyncExec(() -> handler.accept(arg));
                return null;
            }
        };
    }

    private static Browser createBrowser(Composite parent) {
        if ("win32".equals(SWT.getPlatform())) {
            try {
                return new Browser(parent, SWT.EDGE);
            } catch (SWTError e) {
                log.warn("Edge (WebView2) indisponível; usando o navegador padrão do SWT", e);
            }
        }
        return new Browser(parent, SWT.NONE);
    }

    private static String loadPage() {
        return resource("chat.html").replace("/*CSS*/", resource("chat.css")).replace("/*JS*/", resource("chat.js"));
    }

    private static String resource(String name) {
        try (InputStream in = ChatBrowserBridge.class.getResourceAsStream("/resources/chat/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Recurso ausente: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Erro ao ler recurso " + name, e);
        }
    }
}
```

- [ ] **Step 3: Implementar `UiApprovalGate`**

`B/ui/UiApprovalGate.java`:

```java
package io.github.eduardo.dbeaver.aichat.ui;

import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.CancelledException;
import io.github.eduardo.dbeaver.aichat.core.tools.ApprovalGate;
import io.github.eduardo.dbeaver.aichat.core.tools.ApprovalRequest;
import io.github.eduardo.dbeaver.aichat.core.tools.Decision;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import org.eclipse.swt.widgets.Display;

/** Shows an approval card in the chat and blocks the worker thread until the user clicks. */
final class UiApprovalGate implements ApprovalGate {

    private final Display display;
    private final ChatBrowserBridge bridge;
    private final Map<String, CompletableFuture<Decision>> pending = new ConcurrentHashMap<>();

    UiApprovalGate(Display display, ChatBrowserBridge bridge) {
        this.display = display;
        this.bridge = bridge;
    }

    @Override
    public Decision requestApproval(ApprovalRequest request, CancellationToken cancel) {
        String id = UUID.randomUUID().toString();
        CompletableFuture<Decision> future = new CompletableFuture<>();
        pending.put(id, future);
        ui(() -> bridge.addApproval(id, request));
        try (CancellationToken.Registration registration = cancel.onCancel(() -> future.cancel(false))) {
            return future.get();
        } catch (CancellationException e) {
            ui(() -> bridge.closeApproval(id, "Cancelado"));
            throw new CancelledException();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException();
        } catch (ExecutionException e) {
            return Decision.REJECTED;
        } finally {
            pending.remove(id);
        }
    }

    void complete(String id, Decision decision) {
        CompletableFuture<Decision> future = pending.get(id);
        if (future != null) {
            future.complete(decision);
        }
    }

    void rejectAll() {
        pending.values().forEach(f -> f.complete(Decision.REJECTED));
    }

    private void ui(Runnable r) {
        if (!display.isDisposed()) {
            display.asyncExec(() -> {
                if (!bridge.isDisposed()) {
                    r.run();
                }
            });
        }
    }
}
```

- [ ] **Step 4: Implementar `ChatView`**

`B/ui/ChatView.java`:

```java
package io.github.eduardo.dbeaver.aichat.ui;

import io.github.eduardo.dbeaver.aichat.Activator;
import io.github.eduardo.dbeaver.aichat.core.CancellationToken;
import io.github.eduardo.dbeaver.aichat.core.agent.ChatSession;
import io.github.eduardo.dbeaver.aichat.core.agent.Conversation;
import io.github.eduardo.dbeaver.aichat.core.agent.PromptContext;
import io.github.eduardo.dbeaver.aichat.core.agent.SessionListener;
import io.github.eduardo.dbeaver.aichat.core.agent.SystemPromptBuilder;
import io.github.eduardo.dbeaver.aichat.core.history.ConversationStore;
import io.github.eduardo.dbeaver.aichat.core.history.ConversationSummary;
import io.github.eduardo.dbeaver.aichat.core.llm.AssistantTurn;
import io.github.eduardo.dbeaver.aichat.core.llm.ChatMessage;
import io.github.eduardo.dbeaver.aichat.core.llm.ProviderConfig;
import io.github.eduardo.dbeaver.aichat.core.llm.ProviderFactory;
import io.github.eduardo.dbeaver.aichat.core.llm.ToolCall;
import io.github.eduardo.dbeaver.aichat.core.tools.ConnectionInfo;
import io.github.eduardo.dbeaver.aichat.core.tools.Decision;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolContext;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolOutcome;
import io.github.eduardo.dbeaver.aichat.core.tools.ToolRegistry;
import io.github.eduardo.dbeaver.aichat.dbeaver.DBeaverCompat;
import io.github.eduardo.dbeaver.aichat.dbeaver.DBeaverGateway;
import io.github.eduardo.dbeaver.aichat.dbeaver.EditorContext;
import io.github.eduardo.dbeaver.aichat.dbeaver.EditorContextResolver;
import io.github.eduardo.dbeaver.aichat.settings.AiChatPreferencePage;
import io.github.eduardo.dbeaver.aichat.settings.ProviderSettings;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.ElementListSelectionDialog;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.part.ViewPart;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;

public class ChatView extends ViewPart {

    public static final String ID = "io.github.eduardo.dbeaver.aichat.view";

    private static final Log log = Log.getLog(ChatView.class);
    private static final String EDITOR_CONNECTION = "(conexão do editor ativo)";
    private static final int MAX_TOOL_RESULT_CHARS = 4000;
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final DBeaverGateway gateway = new DBeaverGateway();
    private final ToolRegistry tools = ToolRegistry.standard(gateway);
    private final HttpClient http = ProviderFactory.defaultHttpClient();
    private final AtomicInteger domCounter = new AtomicInteger();

    private ConversationStore store;
    private Conversation conversation = Conversation.create();
    private List<ProviderConfig> providers = List.of();
    private List<ConnectionInfo> connections = List.of();
    private CancellationToken running;

    private Display display;
    private Combo providerCombo;
    private Combo connectionCombo;
    private Button includeContextCheck;
    private Label contextLabel;
    private ChatBrowserBridge bridge;
    private UiApprovalGate approvalGate;
    private Text input;
    private Button sendButton;
    private Button stopButton;

    @Override
    public void createPartControl(Composite parent) {
        display = parent.getDisplay();
        store = new ConversationStore(Activator.getDefault().conversationsDir());
        parent.setLayout(new GridLayout(1, false));
        createTopBar(parent);
        createContextBar(parent);
        bridge = new ChatBrowserBridge(parent, new Callbacks());
        bridge.control().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        approvalGate = new UiApprovalGate(display, bridge);
        createInputArea(parent);
        createActions();
        refreshProviders();
        refreshConnections();
        refreshEditorContext();
    }

    @Override
    public void setFocus() {
        input.setFocus();
        refreshProviders();
        refreshConnections();
        refreshEditorContext();
    }

    @Override
    public void dispose() {
        if (running != null) {
            running.cancel();
        }
        if (approvalGate != null) {
            approvalGate.rejectAll();
        }
        gateway.close();
        super.dispose();
    }

    /** Called by "Perguntar à IA": use the active editor's connection and selection. */
    public void focusWithEditorContext() {
        includeContextCheck.setSelection(true);
        connectionCombo.select(0);
        refreshEditorContext();
        if (input.getText().isBlank()) {
            input.setText("Explique o SQL selecionado.");
        }
        input.setFocus();
        input.selectAll();
    }

    private void createTopBar(Composite parent) {
        Composite bar = new Composite(parent, SWT.NONE);
        bar.setLayout(new GridLayout(4, false));
        bar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        new Label(bar, SWT.NONE).setText("Modelo:");
        providerCombo = new Combo(bar, SWT.DROP_DOWN | SWT.READ_ONLY);
        providerCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        providerCombo.addListener(SWT.Selection, e -> {
            int i = providerCombo.getSelectionIndex();
            if (i >= 0 && i < providers.size()) {
                ProviderSettings.setActiveProviderId(providers.get(i).id());
            }
        });
        new Label(bar, SWT.NONE).setText("Conexão:");
        connectionCombo = new Combo(bar, SWT.DROP_DOWN | SWT.READ_ONLY);
        connectionCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        connectionCombo.addListener(SWT.Selection, e -> refreshEditorContext());
    }

    private void createContextBar(Composite parent) {
        Composite bar = new Composite(parent, SWT.NONE);
        bar.setLayout(new GridLayout(2, false));
        bar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        includeContextCheck = new Button(bar, SWT.CHECK);
        includeContextCheck.setText("Usar contexto do editor:");
        includeContextCheck.setSelection(true);
        contextLabel = new Label(bar, SWT.NONE);
        contextLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
    }

    private void createInputArea(Composite parent) {
        Composite area = new Composite(parent, SWT.NONE);
        area.setLayout(new GridLayout(2, false));
        area.setLayoutData(new GridData(SWT.FILL, SWT.BOTTOM, true, false));
        input = new Text(area, SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.BORDER);
        GridData inputData = new GridData(SWT.FILL, SWT.FILL, true, false);
        inputData.heightHint = input.getLineHeight() * 4;
        input.setLayoutData(inputData);
        input.setToolTipText("Ctrl+Enter envia");
        input.addListener(SWT.KeyDown, e -> {
            if ((e.stateMask & SWT.MOD1) != 0 && (e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR)) {
                e.doit = false;
                send();
            }
        });
        Composite buttons = new Composite(area, SWT.NONE);
        buttons.setLayout(new GridLayout(1, true));
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.TOP, false, false));
        sendButton = new Button(buttons, SWT.PUSH);
        sendButton.setText("Enviar");
        sendButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        sendButton.addListener(SWT.Selection, e -> send());
        stopButton = new Button(buttons, SWT.PUSH);
        stopButton.setText("Parar");
        stopButton.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        stopButton.setEnabled(false);
        stopButton.addListener(SWT.Selection, e -> {
            if (running != null) {
                running.cancel();
            }
        });
    }

    private void createActions() {
        IToolBarManager toolbar = getViewSite().getActionBars().getToolBarManager();
        ISharedImages images = PlatformUI.getWorkbench().getSharedImages();
        toolbar.add(action("Nova conversa", images, ISharedImages.IMG_TOOL_NEW_WIZARD, this::newConversation));
        toolbar.add(action("Histórico de conversas", images, ISharedImages.IMG_OBJ_FOLDER, this::openHistory));
        toolbar.add(action("Apagar conversa atual", images, ISharedImages.IMG_TOOL_DELETE, this::deleteConversation));
        toolbar.add(action("Configurações do AI Chat", images, ISharedImages.IMG_DEF_VIEW, this::openPreferences));
    }

    private static Action action(String text, ISharedImages images, String image, Runnable run) {
        Action action = new Action(text) {
            @Override
            public void run() {
                run.run();
            }
        };
        action.setToolTipText(text);
        action.setImageDescriptor(images.getImageDescriptor(image));
        return action;
    }

    private IWorkbenchWindow window() {
        return getSite().getWorkbenchWindow();
    }

    private void refreshProviders() {
        providers = ProviderSettings.loadProviders();
        String active = ProviderSettings.activeProviderId();
        providerCombo.removeAll();
        if (providers.isEmpty()) {
            providerCombo.add("(configure um provedor)");
            providerCombo.select(0);
            return;
        }
        int selection = 0;
        for (int i = 0; i < providers.size(); i++) {
            ProviderConfig p = providers.get(i);
            providerCombo.add(p.name() + " — " + p.model());
            if (p.id().equals(active)) {
                selection = i;
            }
        }
        providerCombo.select(selection);
    }

    private ProviderConfig selectedProvider() {
        int i = providerCombo.getSelectionIndex();
        return i >= 0 && i < providers.size() ? providers.get(i) : null;
    }

    private void refreshConnections() {
        int index = connectionCombo.getSelectionIndex();
        String selectedId = index > 0 && index - 1 < connections.size() ? connections.get(index - 1).id() : null;
        connections = gateway.listConnections();
        connectionCombo.removeAll();
        connectionCombo.add(EDITOR_CONNECTION);
        int selection = 0;
        for (int i = 0; i < connections.size(); i++) {
            ConnectionInfo c = connections.get(i);
            connectionCombo.add(c.name() + "  [" + c.project() + "]");
            if (c.id().equals(selectedId)) {
                selection = i + 1;
            }
        }
        connectionCombo.select(selection);
    }

    private void selectConnection(String connectionId) {
        refreshConnections();
        for (int i = 0; i < connections.size(); i++) {
            if (connections.get(i).id().equals(connectionId)) {
                connectionCombo.select(i + 1);
                return;
            }
        }
        connectionCombo.select(0);
    }

    private String selectedConnectionId(EditorContext active) {
        int index = connectionCombo.getSelectionIndex();
        if (index > 0 && index - 1 < connections.size()) {
            return connections.get(index - 1).id();
        }
        return active != null && active.container() != null ? active.container().getId() : null;
    }

    private void refreshEditorContext() {
        if (contextLabel == null || contextLabel.isDisposed()) {
            return;
        }
        EditorContext ctx = EditorContextResolver.fromActiveEditor(window());
        String text;
        if (ctx == null) {
            text = "nenhum editor SQL ativo";
        } else {
            StringBuilder sb = new StringBuilder(ctx.container() == null ? "editor sem conexão" : ctx.container().getName());
            if (ctx.schema() != null) {
                sb.append(" / ").append(ctx.schema());
            }
            if (ctx.selectedText() != null) {
                sb.append(" · ").append(ctx.selectedText().split("\\R", -1).length).append(" linha(s) selecionada(s)");
            }
            text = sb.toString();
        }
        contextLabel.setText(text);
        contextLabel.getParent().layout();
    }

    private void send() {
        if (running != null) {
            return;
        }
        String text = input.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        ProviderConfig config = selectedProvider();
        if (config == null) {
            MessageDialog.openInformation(getSite().getShell(), "AI Chat", "Configure um provedor de IA primeiro.");
            openPreferences();
            return;
        }
        refreshConnections();
        EditorContext active = EditorContextResolver.fromActiveEditor(window());
        String connectionId = selectedConnectionId(active);
        ConnectionInfo connection = connections.stream().filter(c -> c.id().equals(connectionId)).findFirst().orElse(null);
        boolean useEditor = includeContextCheck.getSelection() && active != null && active.container() != null
                && active.container().getId().equals(connectionId);
        String systemPrompt = SystemPromptBuilder.build(new PromptContext(connection,
                connectionId == null ? null : gateway.dialectName(connectionId),
                useEditor ? active.schema() : null,
                useEditor ? active.selectedText() : null,
                ProviderSettings.toolLimits().maxRows()));

        Conversation current = conversation;
        current.setProviderId(config.id());
        current.setConnectionId(connectionId);
        bridge.addUser(text);
        input.setText("");

        CancellationToken token = new CancellationToken();
        setRunning(token);
        ChatSession session = new ChatSession(current,
                ProviderFactory.create(config, ProviderSettings.apiKey(config.id()), http),
                config, tools, ProviderSettings.sessionSettings());
        ToolContext toolContext = new ToolContext(connectionId, approvalGate, token, ProviderSettings.toolLimits());
        UiListener listener = new UiListener(token);
        Job job = Job.create("AI Chat", monitor -> {
            session.runTurn(text, systemPrompt, toolContext, listener);
            save(current);
            return Status.OK_STATUS;
        });
        job.setSystem(true);
        job.schedule();
    }

    private void setRunning(CancellationToken token) {
        running = token;
        if (!sendButton.isDisposed()) {
            sendButton.setEnabled(token == null);
            stopButton.setEnabled(token != null);
        }
    }

    private void save(Conversation c) {
        try {
            store.save(c);
        } catch (IOException | RuntimeException e) {
            log.error("Could not save AI Chat conversation", e);
        }
    }

    private boolean busy() {
        if (running != null) {
            MessageDialog.openInformation(getSite().getShell(), "AI Chat", "Aguarde ou pare a resposta atual.");
            return true;
        }
        return false;
    }

    private void newConversation() {
        if (busy()) {
            return;
        }
        conversation = Conversation.create();
        bridge.clear();
    }

    private void openHistory() {
        if (busy()) {
            return;
        }
        List<ConversationSummary> list;
        try {
            list = store.list();
        } catch (IOException e) {
            MessageDialog.openError(getSite().getShell(), "AI Chat", "Erro ao ler histórico: " + e.getMessage());
            return;
        }
        ElementListSelectionDialog dialog = new ElementListSelectionDialog(getSite().getShell(), new LabelProvider() {
            @Override
            public String getText(Object element) {
                ConversationSummary s = (ConversationSummary) element;
                return s.title() + "   (" + DATE_FORMAT.format(Instant.ofEpochMilli(s.updatedAt())) + ")";
            }
        });
        dialog.setTitle("Histórico de conversas");
        dialog.setMessage("Escolha uma conversa:");
        dialog.setElements(list.toArray());
        dialog.setMultipleSelection(false);
        if (dialog.open() == Window.OK && dialog.getFirstResult() instanceof ConversationSummary) {
            openConversation(((ConversationSummary) dialog.getFirstResult()).id());
        }
    }

    private void openConversation(String id) {
        try {
            conversation = store.load(id);
        } catch (IOException | RuntimeException e) {
            MessageDialog.openError(getSite().getShell(), "AI Chat", "Erro ao abrir conversa: " + e.getMessage());
            return;
        }
        selectConnection(conversation.connectionId());
        render(conversation);
    }

    private void render(Conversation c) {
        bridge.clear();
        Map<String, String> toolDomIds = new HashMap<>();
        for (ChatMessage m : c.messages()) {
            switch (m.role()) {
                case USER:
                    bridge.addUser(m.text());
                    break;
                case ASSISTANT: {
                    if (!m.text().isBlank()) {
                        String id = "m" + domCounter.incrementAndGet();
                        bridge.startAssistant(id);
                        bridge.setAssistantMarkdown(id, m.text());
                    }
                    for (ToolCall call : m.toolCalls()) {
                        String domId = "t" + domCounter.incrementAndGet();
                        toolDomIds.put(call.id(), domId);
                        bridge.addTool(domId, call.name(), call.argumentsJson());
                    }
                    break;
                }
                case TOOL: {
                    String domId = toolDomIds.remove(m.toolCallId());
                    if (domId != null) {
                        bridge.setToolResult(domId, abbreviate(m.text()), m.error());
                    }
                    break;
                }
                default:
            }
        }
    }

    private void deleteConversation() {
        if (busy()) {
            return;
        }
        if (!MessageDialog.openConfirm(getSite().getShell(), "AI Chat", "Apagar a conversa atual do histórico?")) {
            return;
        }
        try {
            store.delete(conversation.id());
        } catch (IOException e) {
            log.error("Could not delete conversation", e);
        }
        conversation = Conversation.create();
        bridge.clear();
    }

    private void openPreferences() {
        PreferencesUtil.createPreferenceDialogOn(getSite().getShell(), AiChatPreferencePage.ID, null, null).open();
        refreshProviders();
    }

    private void copyToClipboard(String text) {
        Clipboard clipboard = new Clipboard(display);
        try {
            clipboard.setContents(new Object[] {text}, new Transfer[] {TextTransfer.getInstance()});
        } finally {
            clipboard.dispose();
        }
    }

    private static String abbreviate(String text) {
        return text.length() > MAX_TOOL_RESULT_CHARS ? text.substring(0, MAX_TOOL_RESULT_CHARS) + "\n…" : text;
    }

    private void ui(Runnable r) {
        if (display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            if (!bridge.isDisposed()) {
                r.run();
            }
        });
    }

    private final class Callbacks implements ChatBrowserBridge.Callbacks {

        @Override
        public void approve(String id) {
            approvalGate.complete(id, Decision.APPROVED);
        }

        @Override
        public void reject(String id) {
            approvalGate.complete(id, Decision.REJECTED);
        }

        @Override
        public void insertSql(String sql) {
            if (!DBeaverCompat.insertIntoActiveEditor(window(), sql)) {
                openSql(sql);
            }
        }

        @Override
        public void openSql(String sql) {
            DBPDataSourceContainer container =
                    gateway.findContainer(selectedConnectionId(EditorContextResolver.fromActiveEditor(window())));
            if (!DBeaverCompat.openInNewEditor(window(), container, sql)) {
                copyToClipboard(sql);
                MessageDialog.openInformation(getSite().getShell(), "AI Chat",
                        "Não foi possível abrir um editor SQL. O SQL foi copiado para a área de transferência.");
            }
        }

        @Override
        public void copy(String text) {
            copyToClipboard(text);
        }
    }

    /** Receives session events on the worker thread and forwards them to the UI thread. */
    private final class UiListener implements SessionListener {

        private final CancellationToken token;
        private final StringBuilder text = new StringBuilder();
        private final Map<String, String> toolDomIds = new HashMap<>();
        private String messageId;
        private boolean flushScheduled;

        UiListener(CancellationToken token) {
            this.token = token;
        }

        @Override
        public void onAssistantStart() {
            String id = "m" + domCounter.incrementAndGet();
            synchronized (this) {
                messageId = id;
                text.setLength(0);
            }
            ui(() -> bridge.startAssistant(id));
        }

        @Override
        public void onTextDelta(String delta) {
            synchronized (this) {
                text.append(delta);
                if (flushScheduled) {
                    return;
                }
                flushScheduled = true;
            }
            ui(() -> display.timerExec(50, this::flush));
        }

        private void flush() {
            String id;
            String markdown;
            synchronized (this) {
                flushScheduled = false;
                id = messageId;
                markdown = text.toString();
            }
            if (!bridge.isDisposed()) {
                bridge.setAssistantMarkdown(id, markdown);
            }
        }

        @Override
        public void onAssistantEnd(AssistantTurn turn) {
            String id;
            synchronized (this) {
                id = messageId;
            }
            String finalText = turn.text();
            ui(() -> {
                bridge.setAssistantMarkdown(id, finalText);
                bridge.finishAssistant(id);
            });
        }

        @Override
        public void onToolStart(ToolCall call) {
            String domId = "t" + domCounter.incrementAndGet();
            synchronized (this) {
                toolDomIds.put(call.id(), domId);
            }
            ui(() -> bridge.addTool(domId, call.name(), call.argumentsJson()));
        }

        @Override
        public void onToolEnd(ToolCall call, ToolOutcome outcome) {
            String domId;
            synchronized (this) {
                domId = toolDomIds.remove(call.id());
            }
            if (domId == null) {
                return;
            }
            String content = abbreviate(outcome.content());
            ui(() -> bridge.setToolResult(domId, content, outcome.error()));
        }

        @Override
        public void onNotice(String message) {
            ui(() -> bridge.addNotice(message));
        }

        @Override
        public void onError(String message) {
            ui(() -> bridge.addError(message));
        }

        @Override
        public void onTurnFinished() {
            ui(() -> {
                if (running == token) {
                    setRunning(null);
                }
            });
        }
    }
}
```

- [ ] **Step 5: Registrar a view e incluir os recursos no build**

`plugin.xml` — adicionar, dentro de `<plugin>`, antes da extensão de preferências:

```xml
   <extension point="org.eclipse.ui.views">
      <view
            id="io.github.eduardo.dbeaver.aichat.view"
            name="AI Chat"
            category="org.jkiss.dbeaver.core.category"
            class="io.github.eduardo.dbeaver.aichat.ui.ChatView"
            restorable="true"/>
   </extension>
```

`build.properties`:

```properties
source.. = src/
output.. = target/classes/
bin.includes = META-INF/,\
               .,\
               plugin.xml,\
               lib/,\
               resources/
```

- [ ] **Step 6: Compilar e conferir o jar**

```bash
mvn clean verify 2>&1 | grep -E 'ERROR|BUILD' | tail -20
unzip -l bundles/io.github.eduardo.dbeaver.aichat/target/io.github.eduardo.dbeaver.aichat-0.1.0-SNAPSHOT.jar | grep -E 'resources/chat|ChatView'
```

Expected: `BUILD SUCCESS`; o jar contém `resources/chat/chat.html`, `chat.css`, `chat.js` e `ChatView.class`.

- [ ] **Step 7: Commit**

```bash
git add bundles
git commit -m "feat(bundle): AI Chat view with streaming, approvals, tool cards and history" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: Painel à direita, botão na barra de ferramentas, "Perguntar à IA", README, instalação e E2E no DBeaver 24.1.4

**Files:**
- Create: `B/ui/OpenChatHandler.java`, `B/ui/AskAiHandler.java`, `scripts/make-icon.py`, `bundles/io.github.eduardo.dbeaver.aichat/icons/chat.png`, `.../icons/chat@2x.png` (gerados pelo script), `README.md`
- Modify: `bundles/io.github.eduardo.dbeaver.aichat/plugin.xml`, `bundles/io.github.eduardo.dbeaver.aichat/build.properties` (incluir `icons/`)

**Interfaces:**
- Consumes: `ChatView.ID`, `ChatView.focusWithEditorContext()` (Task 12).
- Produces:
  - comando `io.github.eduardo.dbeaver.aichat.openChat` (abre/foca o chat) com botão na barra de ferramentas principal e item no menu Window
  - comando `io.github.eduardo.dbeaver.aichat.askAboutSelection` no menu de contexto do editor SQL
  - extensão de perspectiva: a view fica acoplada **à direita da área de editores** na perspectiva padrão do DBeaver (`org.jkiss.dbeaver.core.perspective`), como o chat do VS Code

Observação: o Eclipse aplica extensões de perspectiva quando a perspectiva é criada ou resetada. Em instalação nova do plugin num workspace que já existe, o painel aparece à direita depois de **Window → Reset Perspective** (uma vez só); depois disso a posição fica salva. O README e o E2E cobrem isso.

- [ ] **Step 1: Implementar `OpenChatHandler`**

`B/ui/OpenChatHandler.java`:

```java
package io.github.eduardo.dbeaver.aichat.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

/** Toolbar/menu action: opens (or focuses) the AI Chat panel. */
public class OpenChatHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindowChecked(event);
        try {
            window.getActivePage().showView(ChatView.ID);
        } catch (PartInitException e) {
            throw new ExecutionException("Não foi possível abrir o AI Chat", e);
        }
        return null;
    }
}
```

- [ ] **Step 2: Implementar `AskAiHandler`**

`B/ui/AskAiHandler.java`:

```java
package io.github.eduardo.dbeaver.aichat.ui;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.handlers.HandlerUtil;

/** Opens the AI Chat view using the active SQL editor's connection and selection as context. */
public class AskAiHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindowChecked(event);
        try {
            ChatView view = (ChatView) window.getActivePage().showView(ChatView.ID);
            view.focusWithEditorContext();
        } catch (PartInitException e) {
            throw new ExecutionException("Não foi possível abrir o AI Chat", e);
        }
        return null;
    }
}
```

- [ ] **Step 3: Gerar o ícone do chat (16 px e 32 px)**

`scripts/make-icon.py`:

```python
#!/usr/bin/env python3
"""Generates the AI Chat icons (16x16 and 32x32 PNG, speech bubble) with the standard library only."""
import struct
import sys
import zlib
from pathlib import Path

BLUE = (47, 123, 216, 255)
WHITE = (255, 255, 255, 255)
CLEAR = (0, 0, 0, 0)


def pixel(x, y):
    """16x16 design: rounded bubble (rows 1-11), three 2x2 dots, tail at bottom-left (rows 12-14)."""
    if 1 <= y <= 11 and 1 <= x <= 14:
        if x in (1, 14) and y in (1, 11):
            return CLEAR
        if y in (5, 6) and x in (4, 5, 7, 8, 10, 11):
            return WHITE
        return BLUE
    if 12 <= y <= 14 and 3 <= x <= 3 + (14 - y):
        return BLUE
    return CLEAR


def chunk(tag, data):
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)


def write_png(size, path):
    scale = size // 16
    rows = []
    for y in range(size):
        row = b"".join(bytes(pixel(x // scale, y // scale)) for x in range(size))
        rows.append(b"\x00" + row)
    data = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"".join(rows)))
            + chunk(b"IEND", b""))
    path.write_bytes(data)


out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
write_png(16, out / "chat.png")
write_png(32, out / "chat@2x.png")
```

Run:

```bash
python3 scripts/make-icon.py bundles/io.github.eduardo.dbeaver.aichat/icons
file bundles/io.github.eduardo.dbeaver.aichat/icons/*.png
```

Expected: `chat.png: PNG image data, 16 x 16, 8-bit/color RGBA` e `chat@2x.png: PNG image data, 32 x 32, ...`. Os PNGs gerados são versionados.

- [ ] **Step 4: Incluir os ícones no build**

`build.properties`:

```properties
source.. = src/
output.. = target/classes/
bin.includes = META-INF/,\
               .,\
               plugin.xml,\
               lib/,\
               resources/,\
               icons/
```

- [ ] **Step 5: `plugin.xml` final**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<?eclipse version="3.4"?>
<plugin>
   <extension point="org.eclipse.ui.views">
      <view
            id="io.github.eduardo.dbeaver.aichat.view"
            name="AI Chat"
            category="org.jkiss.dbeaver.core.category"
            class="io.github.eduardo.dbeaver.aichat.ui.ChatView"
            icon="icons/chat.png"
            restorable="true"/>
   </extension>

   <!-- Docked to the right of the editor area in DBeaver's default perspective, like the VS Code chat. -->
   <extension point="org.eclipse.ui.perspectiveExtensions">
      <perspectiveExtension targetID="org.jkiss.dbeaver.core.perspective">
         <view
               id="io.github.eduardo.dbeaver.aichat.view"
               relative="org.eclipse.ui.editorss"
               relationship="right"
               ratio="0.70"
               visible="true"/>
         <viewShortcut id="io.github.eduardo.dbeaver.aichat.view"/>
      </perspectiveExtension>
   </extension>

   <extension point="org.eclipse.ui.preferencePages">
      <page
            id="io.github.eduardo.dbeaver.aichat.preferences"
            name="AI Chat"
            class="io.github.eduardo.dbeaver.aichat.settings.AiChatPreferencePage"/>
   </extension>

   <extension point="org.eclipse.ui.commands">
      <command
            id="io.github.eduardo.dbeaver.aichat.openChat"
            name="AI Chat"
            description="Abrir o painel de chat de IA"/>
      <command
            id="io.github.eduardo.dbeaver.aichat.askAboutSelection"
            name="Perguntar à IA sobre a seleção"/>
   </extension>

   <extension point="org.eclipse.ui.commandImages">
      <image commandId="io.github.eduardo.dbeaver.aichat.openChat" icon="icons/chat.png"/>
      <image commandId="io.github.eduardo.dbeaver.aichat.askAboutSelection" icon="icons/chat.png"/>
   </extension>

   <extension point="org.eclipse.ui.handlers">
      <handler
            commandId="io.github.eduardo.dbeaver.aichat.openChat"
            class="io.github.eduardo.dbeaver.aichat.ui.OpenChatHandler"/>
      <handler
            commandId="io.github.eduardo.dbeaver.aichat.askAboutSelection"
            class="io.github.eduardo.dbeaver.aichat.ui.AskAiHandler"/>
   </extension>

   <extension point="org.eclipse.ui.menus">
      <!-- Quick-open button in the main toolbar (standard Eclipse location, stable across DBeaver versions). -->
      <menuContribution locationURI="toolbar:org.eclipse.ui.main.toolbar?after=additions">
         <toolbar id="io.github.eduardo.dbeaver.aichat.toolbar" label="AI Chat">
            <command
                  commandId="io.github.eduardo.dbeaver.aichat.openChat"
                  tooltip="Abrir o AI Chat"
                  style="push"/>
         </toolbar>
      </menuContribution>
      <menuContribution locationURI="menu:window?after=additions">
         <command commandId="io.github.eduardo.dbeaver.aichat.openChat" label="AI Chat"/>
      </menuContribution>
      <menuContribution
            locationURI="popup:org.jkiss.dbeaver.ui.editors.sql.SQLEditor.EditorContext?after=sql.extras">
         <command commandId="io.github.eduardo.dbeaver.aichat.askAboutSelection"/>
      </menuContribution>
   </extension>
</plugin>
```

- [ ] **Step 6: Escrever `README.md`**

````markdown
# DBeaver AI Chat

Chat de IA dentro do DBeaver (CE 24.1.0 ou mais novo) com acesso às conexões configuradas.
Use o modelo que quiser: qualquer API compatível com OpenAI (OpenAI, Ollama, LM Studio, OpenRouter, Groq...) ou a API da Anthropic.

## O que a IA pode fazer

- Listar conexões, schemas e tabelas; descrever tabelas (colunas, chaves, índices).
- Rodar consultas de leitura (`SELECT`, `SHOW`, `EXPLAIN`) sozinha, com limite de linhas.
- Comandos de escrita (`INSERT`, `UPDATE`, `DELETE`, DDL) **só depois da sua aprovação** no chat.
  Conexões marcadas como somente leitura no DBeaver nunca recebem escrita.

As consultas rodam num contexto de conexão separado do seu editor: leituras terminam em rollback e
escritas aprovadas são confirmadas (commit) na hora.

## Instalação

1. Baixe `dbeaver-ai-chat.zip` (o update site).
2. No DBeaver: **Help → Install New Software… → Add… → Archive…** e escolha o zip.
3. Marque **DBeaver AI Chat**, avance, aceite o aviso de conteúdo não assinado e reinicie.

## Configuração

1. **Window → Preferences → AI Chat → Adicionar…**
2. Escolha um modelo pronto (Anthropic, OpenAI, Ollama, LM Studio, OpenRouter) ou "Personalizado".
3. Informe o modelo e a chave de API (guardada no Secure Storage do Eclipse) e clique **Testar**.

## Uso

- Clique no botão **AI Chat** (balão azul) na barra de ferramentas ou use **Window → AI Chat**.
  O painel fica acoplado à direita dos editores, como o chat do VS Code.
  Se ele abrir em outro lugar logo após instalar, use **Window → Reset Perspective** uma vez.
- Escolha o modelo e a conexão (por padrão, a do editor SQL ativo).
- Com "Usar contexto do editor" marcado, o schema padrão e o SQL selecionado no editor vão junto com a pergunta.
- No editor SQL: botão direito → **Perguntar à IA sobre a seleção**.
- Blocos SQL da resposta têm os botões **Inserir no editor**, **Abrir em novo editor** e **Copiar**.
- Conversas ficam salvas; use os botões da barra da view para nova conversa, histórico e apagar.

## Privacidade

Metadados e resultados de consultas (até os limites configurados) são enviados ao provedor de IA escolhido.
Com Ollama ou LM Studio, tudo fica na sua máquina.

## Build

```bash
scripts/install-toolchain.sh      # JDK 17 + Maven em ~/.local/opt
scripts/verify.sh 24.1.0          # build + testes contra o DBeaver 24.1.0
scripts/verify.sh latest          # build + testes contra a última versão
```

O update site sai em `releng/update-site/target/`.
````

- [ ] **Step 7: Build final e cópia do update site para o Windows**

```bash
. scripts/env.sh
scripts/fetch-dbeaver.sh 24.1.0
mvn clean verify 2>&1 | grep -E 'Tests run:|ERROR|BUILD' | tail -5
WIN_HOME="$(wslpath "$(cmd.exe /c 'echo %USERPROFILE%' 2>/dev/null | tr -d '\r')")"
cp releng/update-site/target/io.github.eduardo.dbeaver.aichat.update-site-0.1.0-SNAPSHOT.zip "$WIN_HOME/Downloads/dbeaver-ai-chat.zip"
ls -l "$WIN_HOME/Downloads/dbeaver-ai-chat.zip"
```

Expected: `Tests run: 112, Failures: 0`, `BUILD SUCCESS`, zip copiado para a pasta Downloads do Windows.

- [ ] **Step 8: Instalar no DBeaver 24.1.4 (Windows)**

No DBeaver: **Help → Install New Software… → Add… → Archive…** → `Downloads\dbeaver-ai-chat.zip` → marcar **DBeaver AI Chat** → desmarcar "Contact all update sites during install" → Next → Finish → aceitar o aviso de conteúdo não assinado → **Restart Now**.
Para reinstalar uma build nova depois: repetir o processo (o qualifier muda a cada build e o p2 oferece a atualização).

- [ ] **Step 9: Roteiro E2E manual (registrar o resultado de cada item)**

Preparação: **Help → Create Sample Database** (SQLite de exemplo do DBeaver) e um provedor configurado em **Preferences → AI Chat** (Anthropic com `claude-opus-5-5`, ou Ollama local). Clicar **Testar** → "Conexão OK".

| # | Ação | Esperado |
|---|------|----------|
| 1 | **Window → Reset Perspective** (uma vez, após instalar) | Painel "AI Chat" aparece acoplado à direita da área de editores; botão com balão azul visível na barra de ferramentas principal |
| 1a | Fechar o painel e clicar no botão AI Chat da barra de ferramentas | Painel reabre à direita e recebe foco; combos de modelo e conexão preenchidos; sem erro em `.metadata/.log` |
| 1b | Window → AI Chat | Mesmo comportamento do botão |
| 2 | Selecionar a conexão de exemplo e perguntar "quais tabelas existem?" | Cartões `list_tables` (e/ou `list_schemas`) aparecem e resposta lista tabelas reais |
| 3 | "quais os 5 clientes com mais faturas?" | `describe_table` + `run_query`; resposta com números reais; texto chega aos poucos (streaming) |
| 4 | "apague a fatura de id 1" | Cartão amarelo de aprovação com o SQL; **Rejeitar** → nada é apagado e o modelo informa a rejeição |
| 5 | Repetir 4 e **Aprovar** | "Linhas afetadas: 1"; `select count(*)` confirma a remoção |
| 6 | Marcar a conexão como somente leitura (Edit Connection → General → Read-only) e repetir 4 | Ferramenta retorna erro de somente leitura, sem cartão de aprovação |
| 7 | Abrir editor SQL na conexão, selecionar uma consulta, botão direito → **Perguntar à IA sobre a seleção** | View recebe foco com "Explique o SQL selecionado."; rótulo de contexto mostra conexão e linhas selecionadas; resposta comenta o SQL selecionado |
| 8 | Na resposta com bloco ```sql, clicar **Inserir no editor** e depois **Abrir em novo editor** | SQL inserido no cursor do editor; novo console SQL aberto na conexão |
| 9 | Pedir uma pergunta longa e clicar **Parar** durante a resposta | Aviso "Cancelado."; botão Enviar volta; nova pergunta funciona em seguida |
| 10 | Pedir "responda exatamente com: `<b>x</b>` e `![i](https://example.com/i.png)`" | `<b>x</b>` aparece como texto; nenhuma imagem carregada |
| 11 | Fechar e reabrir o DBeaver → Histórico | Conversa listada; ao abrir, mensagens e cartões de ferramentas reaparecem; continuar a conversa funciona |
| 12 | Trocar o provedor (ex.: Anthropic → Ollama) no meio da conversa e perguntar de novo | Resposta normal, sem erro 400 |

Se o item 1 falhar com erro de `Browser`/WebView2, conferir se `WebView2Loader.dll` está na pasta do DBeaver. Se as funções JS não responderem (botões sem efeito), remover a meta CSP de `chat.html`, refazer o build e anotar o achado.

- [ ] **Step 10: Commit**

```bash
git add bundles scripts/make-icon.py README.md
git commit -m "feat(bundle): right-docked chat panel, toolbar button, ask-AI command and README" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 14: Compatibilidade com a última versão do DBeaver

**Files:**
- Modify (só se houver quebra): `B/dbeaver/DBeaverCompat.java` e o ponto de uso

**Interfaces:**
- Consumes: `scripts/verify.sh` (Task 0), build completo (Tasks 1–13).

- [ ] **Step 1: Build contra a última versão**

```bash
scripts/verify.sh latest 2>&1 | grep -E 'version=|Tests run:|ERROR|BUILD' | tail -10
```

Expected: `version=26.x...` (última versão publicada), `Tests run: 112, Failures: 0`, `BUILD SUCCESS`.
Se houver erro de compilação numa chamada ao DBeaver: mover a chamada para `DBeaverCompat` com `catch (RuntimeException | LinkageError e)` e fallback; a versão escolhida tem de compilar contra **as duas** versões (rodar `scripts/verify.sh 24.1.0` de novo depois).

- [ ] **Step 2: Smoke test na última versão (instalação portátil, workspace separado)**

```bash
WIN_HOME="$(wslpath "$(cmd.exe /c 'echo %USERPROFILE%' 2>/dev/null | tr -d '\r')")"
mkdir -p "$WIN_HOME/dbeaver-latest"
curl -fSL -o "$WIN_HOME/dbeaver-latest/dbeaver.zip" "https://dbeaver.io/files/dbeaver-ce-latest-win32.win32.x86_64.zip"
unzip -q -o "$WIN_HOME/dbeaver-latest/dbeaver.zip" -d "$WIN_HOME/dbeaver-latest"
ls "$WIN_HOME/dbeaver-latest"
```

Abrir no Windows `%USERPROFILE%\dbeaver-latest\dbeaver\dbeaver.exe -data %USERPROFILE%\dbeaver-latest-ws` (workspace separado para não migrar o workspace do 24.1.4). Instalar o mesmo `dbeaver-ai-chat.zip` e repetir os itens 1, 1a, 2, 4, 7 e 8 do roteiro da Task 13.
Expected: mesmos resultados. O AI Assistant nativo do DBeaver continua funcionando e não há menus/atalhos duplicados com IDs conflitantes.

- [ ] **Step 3: Commit (se houve ajuste)**

```bash
git add bundles
git commit -m "fix(compat): keep DBeaver API calls working on latest release" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Self-review (feito ao escrever o plano)

- Cobertura da spec: provedores + Secure Storage (T2–T4, T11); 6 tools, read-only, rollback de leitura (T6, T10); loop, limite de 10, aprovação, Parar cancelando HTTP/SQL (T7, T10, T12); prompt com dialeto/schema/seleção (T7, T12); contexto do editor e "Perguntar à IA" (T10, T12, T13); painel à direita + botão na barra de ferramentas (T13); view com Browser, markdown, cartões, inserir/abrir SQL (T12); preferências com CRUD/Testar/limites/aviso (T11); histórico (T8, T12); tratamento de erros (T3, T4, T7, T12); segurança (T5, T9, T10, T11, T12); testes unitários (T2–T9) e matriz 24.1/latest (T0, T14); empacotamento (T1, T13).
- Tipos conferidos entre tasks: `ProviderConfig` (8 campos), `ToolContext` (4), `ToolLimits` (3), `PromptContext` (5), `ChatSession.runTurn(String, String, ToolContext, SessionListener)`, `ChatBrowserBridge.addTool(domId, name, args)`.
- Contagem de testes do core: 12 + 7 + 10 + 44 + 20 + 9 + 5 + 5 = 112.
