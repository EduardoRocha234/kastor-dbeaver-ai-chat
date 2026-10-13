# Kastor - DBeaver AI Chat

Chat de IA dentro do DBeaver (CE 24.1.0 ou mais novo) com acesso às conexões configuradas.
Use o modelo que quiser: qualquer API compatível com OpenAI (OpenAI, Ollama, LM Studio, OpenRouter, Groq...) ou a API da Anthropic.

## O que o Kastor pode fazer

- Listar conexões, schemas e tabelas; descrever tabelas (colunas, chaves, índices).
- Rodar consultas de leitura (`SELECT`, `SHOW`, `EXPLAIN`) sozinha, com limite de linhas.
- Comandos de escrita (`INSERT`, `UPDATE`, `DELETE`, DDL) **só depois da sua aprovação** no chat.
  Conexões marcadas como somente leitura no DBeaver nunca recebem escrita.

As consultas rodam num contexto de conexão separado do seu editor: leituras terminam em rollback e
escritas aprovadas são confirmadas (commit) na hora.

## Instalação

1. Baixe `dbeaver-ai-chat.zip` (o update site).
2. No DBeaver: **Help → Install New Software… → Add… → Archive…** e escolha o zip.
3. Marque **Kastor - DBeaver AI Chat**, avance, aceite o aviso de conteúdo não assinado e reinicie.

## Configuração

1. **Window → Preferences → Kastor → Adicionar…**
2. Escolha um modelo pronto (Anthropic, OpenAI, Ollama, LM Studio, OpenRouter) ou "Personalizado".
3. Informe o modelo e a chave de API (guardada no Secure Storage do Eclipse) e clique **Testar**.

## Uso

- Clique no botão **Kastor** (balão azul) na barra de ferramentas ou use **Window → Kastor**.
  O painel fica acoplado à direita dos editores, como o chat do VS Code.
  Se ele abrir em outro lugar logo após instalar, use **Window → Reset Perspective** uma vez.
- No cabeçalho do painel, escolha o modelo e a conexão (por padrão, a do editor SQL ativo).
- Com "Contexto do editor" ligado, o schema padrão e o SQL selecionado no editor vão junto com a pergunta.
- **Enter** envia; **Shift+Enter** quebra a linha.
- Atalhos: **Ctrl+Alt+K** abre o Kastor de qualquer lugar (mude em Preferences → General → Keys);
  no chat, **Ctrl+Alt+C** abre o seletor de conexão e **Ctrl+Alt+M** o de modelo.
- Digite **@** para referenciar objetos da conexão: a lista filtra conforme você digita e navega
  banco → schema → tabela, carregando só o nível escolhido.
- No navegador de banco de dados: botão direito em um banco, schema ou tabela → **Referenciar no Kastor**
  (ou **Ctrl+Alt+R**, que também funciona com a tabela aberta no editor). O Kastor abre na conexão do objeto
  com `@banco.schema.tabela` no campo de texto.
- No editor SQL: botão direito → **Perguntar ao Kastor sobre a seleção**.
- Blocos SQL da resposta têm os botões **Inserir no editor**, **Abrir em novo editor** e **Copiar**.
- Conversas ficam salvas: o botão ☰ abre a lista (busca, abrir, apagar) e o + inicia uma nova.

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
