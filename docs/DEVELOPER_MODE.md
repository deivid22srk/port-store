# Modo Desenvolvedor — Exportar e Importar dados dos jogos

O Modo Desenvolvedor expõe, no Game Launcher, a exportação e a importação dos
dados de cada jogo virtual em **UM ÚNICO ARQUIVO `.tar.zst`**. O formato é o
mesmo que a futura **Loja** vai distribuir (mesmo manifest embutido, mesmo
pipeline de verificação/extração).

## 1. Ativar

**Configurações → Modo Desenvolvedor** (switch, desligado por padrão). Ao
ativar aparece o aviso *"Recursos avançados. Use apenas se souber o que está
fazendo."* O estado é persistente (DataStore). Com o modo **desligado** as
opções de exportar/importar não aparecem no menu dos jogos.

## 2. Formato do pacote (v2 — arquivo único)

```
<onde o usuário escolher>/<prefixo>_portstore.tar.zst      ← 1 ÚNICO ARQUIVO
```

Estrutura interna (streaming puro — nunca materializada em disco):

```
arquivo .tar.zst = frames Zstandard CONCATENADOS (um frame por entrada TAR)
entrada 0: manifest.json        ← EMBUTIDO no pacote (inspecionável barato)
entrada 1..N: dados do jogo     (install/, saves/, settings/, launcher/)
```

- `<prefixo>` = `gameId` sanitizado (letras/números/`_`).
- **Um frame Zstandard por entrada TAR**: fronteira de retomada + nível de
  compressão por arquivo — conteúdo já comprimido (`.mp4`, `.zip`, `.obb`,
  `.jpg`...) usa nível mínimo ("quase store"); o resto usa o nível escolhido
  (Rápido 3 / Equilibrado 6 / Máximo 19).
- **Cada frame carrega checksum XXH64**, validado automaticamente pelo
  leitor — corrupção/truncamento interrompem a leitura com erro claro.
- **SHA-256 do pacote completo** é calculado no mesmo stream da escrita
  (nada é relido no final) e guardado no estado do job.

```json
{
  "formatVersion": 2,
  "gameId": "...", "gameName": "...", "packageName": "com.example.game",
  "appVersion": "1.2.3", "createdAt": "2026-10-12T00:00:00-03:00",
  "compression": "zstd", "compressionLevel": 3,
  "totalSizeExtracted": 0, "fileCount": 0,
  "includes": ["install", "saves", "settings", "launcher"]
}
```

O manifest é a **primeira entrada do TAR** — ler só ele custa decodificar o
primeiro frame (KBs), mesmo em pacotes de 20 GB.

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

Toque longo no jogo → **Exportar dados do jogo**. Marque as categorias, o
nível de compressão e toque em **Escolher destino…** — o SAF abre em modo
*Criar documento* (nome sugerido `<prefixo>_portstore.tar.zst`). O progresso
aparece em **Downloads → Operações de dados** (barra, %, velocidade, tempo
restante) e na **notificação do serviço foreground** (tipo `dataSync`), com
botão **Cancelar** (apaga o arquivo). Nada dobra o armazenamento: o fluxo é
`origem → TAR → zstd → arquivo único`, direto, sem temporários.

## 5. Importar

Toque longo no jogo → **Importar dados do jogo** → escolha o **arquivo
único do pacote** (`.tar.zst`). O resumo é lido do manifest embutido (jogo,
versão, o que inclui, tamanhos, espaço livre) sem ler os dados. Opções:

- **Fazer backup dos saves antes** — grava `filesDir/backup_saves/<prefixo>_saves_<data>.tar.zst`
  (mesmo formato v2, manifest embutido) antes de substituir;
- **Apagar o pacote após importar** — remove o `.tar.zst` quando termina.

A extração é **direta no destino final** (`zstd → TAR → arquivos`), sem
arquivo intermediário. O `base.apk` vai brevemente para o cache do Bcore e o
**instalador oficial do Bcore** registra o jogo (recria app dir + libs +
`package.conf`) — os saves existentes são preservados.

## 6. Serviço foreground e notificação

Durante TODA a exportação/importação roda o `BackupForegroundService`
(`foregroundServiceType="dataSync"`):

- iniciado no instante em que o job entra na fila e de novo pelos workers
  (por garantia) — `startForeground` imediato, dentro da janela de 5 s;
- a notificação mostra **%, bytes, velocidade, tempo restante e Cancelar**,
  atualizada direto do barramento de progresso (independe da saúde do
  worker — a notificação não morre com ele);
- ao terminar (concluído/erro/cancelado, sem jobs ativos) o serviço se
  encerra sozinho.

## 7. Retomada e erros

- O progresso é salvo em `filesDir/backup_jobs/` a cada entrada concluída:
  se o app fechar (ou morrer), ao reabrir a operação **volta à fila e
  continua**.
  - **Export**: o arquivo é truncado no offset da última entrada commitada
    (remove o frame parcialmente escrito) e os frames continuam dali.
  - **Import**: o pacote é reaberto e as entradas já extraídas são puladas
    (só decodificadas — extrair é o passo caro, não decodificar).
- **Erro inesperado** → estado visível com mensagem (o card **nunca** fica
  preso em "Preparando…"); "Tentar novamente" refaz só o que falta.
- **Espaço insuficiente** → erro antes de começar (quando o volume permite
  medir).
- Pacote truncado/corrompido → o checksum de frame falha durante a leitura,
  com erro claro, sem tocar nos dados do jogo.
- **Cancelar** na exportação apaga o arquivo; na importação mantém o que já
  foi extraído (a retomada completa o resto).

## 8. Preparação para a Loja

O pipeline da Loja será o mesmo do import: `HTTP (OkHttp, Range) → zstd →
TAR → destino`, reaproveitando `PackageReader`/`ImportManager` e a seção
"Operações de dados" da aba Downloads. O SHA-256 do pacote (calculado na
exportação) será o campo de integridade do catálogo da Loja. Pacotes
separados por categoria (APK / dados / saves) são gerados exportando com
`includes` distintos — o formato de arquivo é idêntico.

## 9. Como testar

1. Ative o Modo Desenvolvedor em Configurações (confirme o aviso).
2. Toque longo num jogo virtual → **Exportar dados do jogo**; deixe
   Instalação + Saves + Configurações marcadas, nível Rápido → **Escolher
   destino…** → confirme o nome sugerido.
3. **A notificação** deve aparecer imediatamente ("Preparando…" e depois %,
   velocidade e tempo restante) e continuar viva com o app em segundo plano.
4. Acompanhe em **Downloads → Operações de dados**; teste o **Cancelar**
   num segundo teste (o arquivo é apagado).
5. Confira o arquivo único: `zstd -d arquivo.tar.zst | tar -tvf -` lista
   `manifest.json` na frente e depois os dados.
6. Desinstale o jogo virtual (menu do jogo) para simular aparelho novo →
   toque longo no jogo (agora não instalado) → **Importar dados do jogo** →
   escolha o **arquivo** do pacote → confirme.
7. O jogo deve voltar com selo **Instalado**, saves/progresso intactos.
8. Interrompa a exportação no meio (feche o app) e reabra: o card em
   Operações de dados volta a rodar e **continua do offset salvo** (o tempo
   até o primeiro % é curto). Faça o mesmo na importação.
