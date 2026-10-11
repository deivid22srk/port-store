# Modo Desenvolvedor — Exportar e Importar dados dos jogos

O Modo Desenvolvedor expõe, no Game Launcher, a exportação e a importação dos
dados de cada jogo virtual em **pacotes `.tar.zst` particionados**. O formato é
o mesmo que a futura **Loja** vai distribuir (mesmo `manifest.json`, mesmas
partes, mesmo pipeline de verificação/extrairão).

## 1. Ativar

**Configurações → Modo Desenvolvedor** (switch, desligado por padrão). Ao
ativar aparece o aviso *"Recursos avançados. Use apenas se souber o que está
fazendo."* O estado é persistente (DataStore). Com o modo **desligado** as
opções de exportar/importar não aparecem no menu dos jogos.

## 2. Formato do pacote

```
<pasta escolhida>/<prefixo>_portstore/
  manifest.json
  <prefixo>.part001.tar.zst
  <prefixo>.part002.tar.zst
  ...
```

- `<prefixo>` = `gameId` sanitizado (letras/números/`_`).
- Cada parte é um **TAR completo** com marcador de fim: pode ser extraída
  **sozinha** (`zstd -d parte | tar -x`), porque o corte entre partes acontece
  sempre na fronteira de uma entrada (um arquivo nunca é dividido).
- Compressão **Zstandard** com um frame por arquivo: conteúdo já comprimido
  (`.mp4`, `.zip`, `.obb`, `.jpg`...) usa nível mínimo ("quase store") para
  economizar tempo; o resto usa o nível escolhido (Rápido 3 / Equilibrado 6 /
  Máximo 19).
- SHA-256 de cada parte calculado **no mesmo stream da escrita** (nada é
  relido no final) e registrado no manifest:

```json
{
  "formatVersion": 1,
  "gameId": "...", "gameName": "...", "packageName": "com.example.game",
  "appVersion": "1.2.3", "createdAt": "2026-10-11T18:00:00-03:00",
  "compression": "zstd", "compressionLevel": 3, "partSizeBytes": 1073741824,
  "totalSizeCompressed": 0, "totalSizeExtracted": 0,
  "parts": [
    { "name": "jogo.part001.tar.zst", "sizeCompressed": 0,
      "sizeExtracted": 0, "sha256": "..." }
  ],
  "includes": ["install", "saves", "settings", "launcher"]
}
```

## 3. O que cada categoria exporta (mapeamento do ambiente virtual)

| Categoria | Origem no Bcore | Caminho no TAR |
|---|---|---|
| Instalação / dados | `base.apk` | `install/apk/base.apk` |
| Instalação / dados | OBB virtual | `install/obb/...` |
| Instalação / dados | `Android/data` virtual | `install/extdata/...` |
| Instalação / dados | `files/` etc. do data dir | `install/data/...` |
| Saves | `databases/` do jogo | `saves/databases/...` |
| Configurações | `shared_prefs/` do jogo | `settings/shared_prefs/...` |
| Dados do launcher | vínculo gameId↔pacote | `launcher/registry.json` |

`cache/`, `code_cache/` e `lib/` ficam de fora (regeneráveis/instalados pelo
motor).

## 4. Exportar

Toque longo no jogo → **Exportar dados do jogo**. Marque as categorias, nível
de compressão, tamanho das partes (500 MB / 1 GB / 2 GB) e escolha o destino
via **SAF** (sem permissão de armazenamento total). O progresso aparece na
aba **Downloads → Operações de dados** (barra, %, velocidade, tempo restante)
e na notificação, com botão **Cancelar** (apaga as partes já geradas).

## 5. Importar

Toque longo no jogo → **Importar dados do jogo** → escolha a **pasta do
pacote** (a que contém `manifest.json`). O resumo mostra jogo, versão, o que
inclui, partes encontradas, tamanhos e espaço livre. Opções:

- **Fazer backup dos saves antes** — grava `_saves_backup_<id>_<data>/` na
  própria pasta do pacote antes de substituir;
- **Apagar cada parte após extrair** — evita ocupar o dobro do espaço.

A extração é **direta no destino final** (hash → zstd → TAR → arquivos), sem
arquivo intermediário. O `base.apk` vai brevemente para o cache do Bcore e o
**instalador oficial do Bcore** registra o jogo (recria app dir + libs +
`package.conf`) — os saves existentes são preservados.

## 6. Retomada e erros

- O progresso por parte é salvo em `filesDir/backup_jobs/`: se o app fechar
  (ou morrer), ao reabrir a operação **volta à fila e continua da última
  parte não concluída** (WorkManager + estado persistido).
- **Hash inválido** → erro claro; "Tentar novamente" refaz **só a parte
  problemática** (as outras não são tocadas).
- **Espaço insuficiente** → erro antes de começar (quando o volume permite
  medir).
- Parte ausente / manifest corrompido → erro listado no diálogo, sem tocar
  nos dados do jogo.

## 7. Preparação para a Loja

O pipeline da Loja será o mesmo do import: `HTTP (OkHttp, Range) → hash →
zstd → TAR → destino`, reaproveitando `ChunkReader`/`ImportManager` e a seção
"Operações de dados" da aba Downloads. Pacotes separados por categoria
(APK / dados / saves) são gerados exportando com `includes` distintos —
o formato de arquivo é idêntico.

## 8. Como testar

1. Ative o Modo Desenvolvedor em Configurações (confirme o aviso).
2. Toque longo num jogo virtual → **Exportar dados do jogo**; deixe
   Instalação + Saves + Configurações marcadas, nível Rápido, partes de 500 MB
   (para gerar mais de uma parte) → **Escolher destino** (Downloads do
   aparelho).
3. Acompanhe em **Downloads → Operações de dados**; teste o **Cancelar** num
   segundo teste (as partes são apagadas).
4. Confira a pasta: `manifest.json` + partes; abra uma parte com
   `zstd -d ... | tar -tvf -` (cada parte lista arquivos completos).
5. Desinstale o jogo virtual (menu do jogo) para simular aparelho novo →
   toque longo no jogo (agora não instalado) → **Importar dados do jogo** →
   escolha a pasta do pacote → confirme.
6. O jogo deve voltar com selo **Instalado**, saves/progresso intactos.
7. Interrompa a importação no meio (feche o app) e reabra: o card em
   Operações de dados volta a rodar e termina do ponto onde parou.
