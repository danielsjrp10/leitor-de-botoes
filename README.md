# Leitor de Botões

App Android que trabalha junto com o TalkBack: quando o foco de acessibilidade
cai em um botão ou ícone **sem rótulo** em qualquer outro aplicativo, ele tenta
identificar o que é (lendo o texto dentro do botão via OCR) e anuncia isso por
voz, complementando o que o TalkBack já fala.

Tudo roda **no próprio aparelho** — nenhuma captura de tela é enviada para a
internet.

## Como o app funciona (resumo técnico)

1. `LabelHelperService` é um "Accessibility Service", a mesma categoria de
   serviço do próprio TalkBack, com permissão para observar o foco em
   qualquer app.
2. Quando o elemento focado não tem `text` nem `contentDescription`, e parece
   ser algo clicável (botão, ícone, etc.), o serviço:
   - Tira uma captura de tela (`takeScreenshot`, disponível a partir do
     Android 11).
   - Recorta só a área daquele elemento.
   - Roda reconhecimento de texto (ML Kit, OCR local) nessa área.
3. Se encontrar texto, anuncia: "Provavelmente: (texto)".
   Se não encontrar nada, avisa que é um botão sem rótulo (provável ícone).

## Limitações atuais (é uma primeira versão)

- **Só funciona a partir do Android 11 (API 30)**, por causa da API de
  captura de tela usada.
- **Ícones puros** (sem nenhum texto dentro, tipo um desenho de lixeira ou
  engrenagem) ainda não são identificados — só o OCR de texto está pronto.
  O próximo passo seria adicionar reconhecimento de ícones (ML Kit Image
  Labeling ou um modelo próprio), mas isso exige treinar/ajustar um modelo
  para ícones de interface, o que é mais trabalhoso e menos confiável.
- A precisão do OCR depende do contraste e do tamanho do texto no botão.

## Limitações conhecidas (decisão registrada)

**Ícones sem nenhum texto (ex: uma seta, uma engrenagem, três linhas de menu)
não são identificados.** Isso foi investigado e decidido conscientemente, não
é um esquecimento:

- O ML Kit Image Labeling (reconhecedor de imagens local do Google) foi
  descartado depois de pesquisa: seu modelo é treinado em fotos do mundo real
  (categorias como "fogueira", "aquário", "roda-gigante"), não em ícones de
  interface - rodar isso em recortes de ícones muito provavelmente não geraria
  palpites úteis.
- Verificamos como o próprio Google resolve esse mesmo problema no TalkBack: a
  única solução existente usa IA generativa, seja em um servidor (exige
  internet) ou localmente, mas só em aparelhos Pixel 9 ou mais novos. Ou seja,
  nem o Google conseguiu resolver "reconhecer ícone sem texto, offline, em
  qualquer aparelho, sem IA generativa" - confirmando que essa é uma limitação
  técnica real da combinação de requisitos deste projeto (offline + qualquer
  aparelho + sem IA generativa), não falta de esforço.

**Recomendação complementar:** o TalkBack (desde a versão 12.1) já tem um
recurso nativo de transcrição automática de texto em imagens sem rótulo,
processado no aparelho, sem internet. Vale ativar em Configurações >
Acessibilidade > TalkBack > Configurações > Áudio > Descrições automáticas -
é gratuito, do próprio Android, e complementa (não substitui) o que este app
faz para elementos clicáveis em geral (não só imagens).



Este projeto já vem com um arquivo em `.github/workflows/build-apk.yml` que
faz o **GitHub** compilar o APK pra você, nos servidores dele. Você só
precisa enviar o código pra lá — nenhuma instalação de SDK, nenhum programa
pesado no seu computador ou celular.

Veja o guia detalhado mais abaixo em "Passo a passo sem Android Studio".

## Assinatura da build (Release)

A partir desta versão, o projeto compila uma build **release** (menor, otimizada
com R8/minificação). Para o Android aceitar instalar essa build, ela precisa
estar **assinada** com uma chave (keystore) — e essa chave é sua, gerada e
guardada só por você. Eu (o assistente) não crio, não guardo e nunca vejo essa
senha.

### 1. Gerar sua própria keystore

No seu computador, com o **Java (JDK) instalado** (o mesmo que o Android
Studio usa, ou o OpenJDK 17), abra o terminal e rode:

```
keytool -genkeypair -v -keystore leitor-de-botoes-release.keystore -alias leitorDeBotoes -keyalg RSA -keysize 2048 -validity 10000
```

Ele vai perguntar:
- Uma **senha da keystore** (escolha uma e guarde — ex: num gerenciador de senhas).
- Alguns dados (nome, organização, cidade...) — pode preencher ou só apertar
  Enter para pular cada um.
- Uma **senha da chave** — pode apertar Enter para usar a mesma da keystore.

Ao final, um arquivo `leitor-de-botoes-release.keystore` estará na pasta atual.
**Guarde esse arquivo e as senhas para sempre** — se perdê-los, futuras
atualizações do app não poderão usar a mesma assinatura.

### 2. Converter a keystore para texto (para colar como Secret)

O GitHub só aceita texto em Secrets, então a keystore (um arquivo binário)
precisa virar texto em Base64:

- **Windows (PowerShell):**
  ```
  [Convert]::ToBase64String([IO.File]::ReadAllBytes("leitor-de-botoes-release.keystore")) | Set-Clipboard
  ```
  (isso já copia o resultado pra área de transferência)

- **Mac:**
  ```
  base64 -i leitor-de-botoes-release.keystore | pbcopy
  ```

- **Linux:**
  ```
  base64 -w0 leitor-de-botoes-release.keystore
  ```
  (copie manualmente o texto que aparecer)

### 3. Cadastrar os Secrets no GitHub

No repositório, vá em **Settings > Secrets and variables > Actions > New
repository secret** e crie estes quatro, um de cada vez:

| Nome do Secret               | Valor                                          |
|-------------------------------|-------------------------------------------------|
| `RELEASE_KEYSTORE_BASE64`     | o texto Base64 copiado no passo 2               |
| `RELEASE_KEYSTORE_PASSWORD`   | a senha da keystore que você escolheu           |
| `RELEASE_KEY_ALIAS`           | `leitorDeBotoes` (ou o alias que você usou)     |
| `RELEASE_KEY_PASSWORD`        | a senha da chave que você escolheu              |

Esses valores ficam criptografados pelo próprio GitHub — nem eu, nem ninguém
com acesso ao repositório, consegue vê-los depois de salvos.

### 4. Compilar

Depois de cadastrar os quatro secrets, é só criar e enviar uma nova tag (ex:
`git tag v0.3 && git push origin v0.3`) — a próxima build automática já vai
sair assinada e instalável.

**Sem os secrets configurados**, a build de release ainda é gerada
normalmente (para verificar que o código compila), mas fica **sem
assinatura** — o Android vai recusar instalar até ela ser assinada.



Isso aqui usa só o **Terminal** (tela de comandos em texto), que funciona bem
com leitor de tela. Você não vai precisar instalar o Android Studio nem o
SDK do Android — quem compila é o próprio GitHub, nos servidores dele.

### 1. Instalar o Git no seu computador

- **Windows:** baixe em https://git-scm.com/download/win e siga o instalador
  (é um assistente padrão, com botões "Next"/"Avançar").
- **Mac:** abra o Terminal e digite `xcode-select --install`, depois
  confirme a instalação.
- **Linux:** abra o terminal e digite `sudo apt install git` (Ubuntu/Debian)
  ou o equivalente da sua distribuição.

### 2. Criar uma conta no GitHub (se ainda não tiver)

Acesse https://github.com/signup e crie sua conta. Guarde o nome de usuário
que você escolher.

### 3. Criar um token de acesso (substitui a senha no envio de código)

1. No site do GitHub, vá em: Settings (Configurações da conta) > Developer
   settings > Personal access tokens > Tokens (classic).
2. Clique em "Generate new token" > "Generate new token (classic)".
3. Dê um nome qualquer, marque a opção **repo**, e gere o token.
4. **Copie o token gerado e guarde num lugar seguro** — ele só aparece uma
   vez, e vai substituir sua senha daqui a pouco.

### 4. Criar o repositório no GitHub

1. No site, clique no `+` no canto superior direito > "New repository".
2. Nome: `leitor-de-botoes` (ou o que preferir).
3. Deixe como **Public**.
4. **Não marque** a opção de criar README — vamos enviar os arquivos que já
   temos.
5. Clique em "Create repository".

### 5. Extrair e enviar o projeto

1. Baixe e descompacte o arquivo `LabelHelper.zip` que te enviei em uma
   pasta no seu computador.
2. Abra o Terminal (Prompt de Comando/PowerShell no Windows, Terminal no
   Mac/Linux) e navegue até essa pasta, por exemplo:
   ```
   cd Downloads/LabelHelper
   ```
3. Rode estes comandos, um de cada vez (troque `SEU-USUARIO` pelo seu nome
   de usuário do GitHub):
   ```
   git init
   git add .
   git commit -m "Primeira versão"
   git branch -M main
   git remote add origin https://github.com/SEU-USUARIO/leitor-de-botoes.git
   git push -u origin main
   ```
4. Quando ele pedir usuário e senha: use seu usuário do GitHub, e **cole o
   token** (do passo 3) no lugar da senha.

### 6. Disparar a compilação do APK

Ainda no terminal, na mesma pasta:
```
git tag v0.1
git push origin v0.1
```
Isso avisa o GitHub para compilar o APK. Leva uns 3 a 5 minutos.

### 7. Baixar o APK pronto

1. No site do GitHub, entre no seu repositório e clique em "Releases" (fica
   na barra lateral direita da página do repositório).
2. Abra a versão "v0.1" e baixe o arquivo `app-debug.apk`.
3. Transfira esse arquivo pro seu celular (por e-mail, Google Drive, ou
   abrindo o mesmo link de Releases direto no navegador do celular).

### 8. Instalar no celular

1. Abra o arquivo `.apk` baixado no celular.
2. O Android vai perguntar se você permite instalar apps de "fontes
   desconhecidas" para aquele app específico — confirme.
3. Toque em "Instalar".
4. Abra o app "Leitor de Botões" e siga o mesmo passo de ativação explicado
   mais abaixo, em "Como ativar".

**Nota:** eu não consigo compilar o projeto aqui do meu lado pra testar
antes (não tenho acesso às ferramentas do Android), então existe uma chance
pequena de dar algum erro na primeira compilação. Se isso acontecer, é só
copiar e colar aqui a mensagem de erro que aparece na aba "Actions" do
GitHub que eu ajusto o código.

## Como compilar e instalar com Android Studio (alternativa)

Se preferir (ou já tiver ajuda de alguém que use), você também pode usar o
**Android Studio** (gratuito, da Google) em um computador. Passo a passo:

1. Baixe e instale o Android Studio: https://developer.android.com/studio
2. Abra o Android Studio e escolha **Open** (Abrir projeto existente).
3. Selecione a pasta `LabelHelper` (a pasta raiz deste projeto).
4. Espere o Gradle sincronizar (baixa as dependências automaticamente — a
   primeira vez demora alguns minutos).
5. Conecte seu celular Android ao computador via USB, com a **Depuração USB**
   ativada (Configurações > Sobre o telefone > toque 7x em "Número da versão"
   para liberar as Opções do desenvolvedor, depois ative "Depuração USB").
6. No Android Studio, clique no botão verde de "Run" (▶) com seu aparelho
   selecionado na lista de dispositivos.
7. O app será instalado e aberto automaticamente no celular.

## Como ativar

1. Abra o app "Leitor de Botões" no celular.
2. Toque em "Abrir configurações de acessibilidade".
3. Encontre "Leitor de Botões" na lista de serviços instalados e ative.
4. O Android vai avisar que o serviço pode ver o conteúdo da tela — isso é
   esperado e necessário para o app funcionar; sem essa permissão, ele não
   consegue identificar os elementos sem rótulo.
5. Pronto — navegue pelos outros apps com o TalkBack normalmente. Quando cair
   num botão sem rótulo, o Leitor de Botões vai complementar com a
   identificação por voz.

## Estrutura do projeto

```
LabelHelper/
├── app/
│   ├── build.gradle.kts          # dependências do app (inclui ML Kit)
│   └── src/main/
│       ├── AndroidManifest.xml   # registra o serviço de acessibilidade
│       ├── java/com/labelhelper/app/
│       │   ├── LabelHelperService.kt   # o coração do app
│       │   └── MainActivity.kt         # tela única, leva às configurações
│       └── res/
│           ├── xml/accessibility_service_config.xml
│           └── values/strings.xml
├── build.gradle.kts
└── settings.gradle.kts
```
