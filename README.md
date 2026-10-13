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
