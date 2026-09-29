# Come eseguire i check

Questa guida raccoglie tutti i comandi diagnostici del progetto. I check non
generano il dataset: servono a osservare i dati intermedi della pipeline e a
capire con precisione che cosa viene letto o calcolato.

## Prima di iniziare

Aprire PowerShell nella cartella del progetto:

```powershell
cd C:\Users\andre\Desktop\ISW2\DataSetConstruction
```

I comandi che fanno checkout (`checkout`, `checkouts`, `scanner`, `metrics`,
`labels`, `path-history`)
richiedono che il clone configurato di Storm sia pulito. Il programma lo
controlla prima di modificare il clone e si ferma se trova file locali
modificati o non tracciati.

La forma generale del comando e':

```powershell
mvn -q exec:java "-Dexec.mainClass=checkANDanalysis.CheckFunctionality" "-Dexec.args=NOME_CHECK"
```

Ogni esecuzione compare sia nella console sia in `CheckResults\NOME_CHECK.txt`.
Il file viene aggiornato a ogni nuova esecuzione dello stesso check: rimane
quindi sempre disponibile il risultato piu' recente e leggibile.

## Generazione controllata del dataset

Solo dopo avere letto i check, la classe `DatasetBuilder` crea il CSV. Essa
applica esattamente le stesse fonti della pipeline: release e date da Jira,
codice e ancestry dai tag Git, classi production dallo scanner, SZZ e resolver
storico prima della label finale.

Per una prova sulle prime tre release training:

```powershell
mvn -q exec:java "-Dexec.mainClass=DatasetBuilder" "-Dexec.args=--release-limit=3"
```

Per tutte le 14 release training, senza argomenti:

```powershell
mvn -q exec:java "-Dexec.mainClass=DatasetBuilder"
```

Il CSV viene scritto in `output\storm\storm_dataset.csv`. Dopo ogni prova
eseguire `csv`: controlla righe, path, duplicati, valori non finiti e
distribuzione delle label.

## Check disponibili

| Check | Comando da inserire al posto di `NOME_CHECK` | Cosa osserva |
|---|---|---|
| Tutti i check rapidi | `all` | Release, tag, scanner, ticket, commit, Proportion, metriche e CSV. SZZ e labeling sono esclusi perche' lenti. |
| Release | `releases` | Indice, nome, Jira ID, data, ordine cronologico e prime/ultime release del training set. |
| Tag Git | `tags` | Tutti i tag, mapping release-tag, tag mancanti, ambigui o riutilizzati. |
| Commit prima release | `release-commit` | Tag, hash, autore, messaggio, data commit e distanza dalla data di release della prima release training. |
| Commit tutte le release | `release-commits` | Per tutte le release: `release`, `tag`, `commit`, `commitDate`, `releaseDate`, `delta giorni`. Non fa checkout. |
| Checkout singolo | `checkout` | Tag risolto, HEAD, branch, cartella del clone e stato pulito dopo il ripristino. |
| Checkout di tutte le release | `checkouts` | Per ogni release: tag, HEAD effettivo, date e delta; al termine mostra `Checkout riusciti: X/Y`. |
| Checkout campione | `checkouts --limit=3` | Stesso check, limitato alle prime tre release. E' il test consigliato prima del controllo completo. |
| Scanner Java | `scanner` | Tutti i `.java` fisicamente presenti, file accettati da `isJavaProductionFile()`, test esclusi, esempi e confronto tra filtro e scanner. |
| Scanner tutte le release training | `scanner-all` | Per ogni release training: `.java` totali, production ed esclusi; esercita il filtro anche su strutture storiche differenti. |
| Unicita' nomi classi | `class-names` | Per ciascuna delle 14 release training conta filename/simple class name univoci e duplicati; per ogni nome duplicato mostra tutti i path production. Non usa il filename come mapping. |
| Ticket Jira | `tickets` | Totali Jira, bug fixed, distribuzioni type/status/resolution, AV/FV presenti o mancanti e ticket campione. |
| Mapping AV/FV | `versions` | Conta Affected Version e Fix Version dichiarate, risolte verso le 41 release note e non riconosciute. |
| Mapping Opening Version | `opening-version` | Conteggia OV determinate, `OV_AFTER_CORPUS` e `NO_OV`; mostra esempi e 15 casi `FV_LE_OV` per diagnosi, senza modificare l'algoritmo. |
| Fixing commit | `commits` | Ticket con/senza commit, inclusi gli esclusi dal labeling per assenza di fixing commit; numero di fixing commit, commit condivisi e classi Java production toccate. |
| Proportion Total | `proportion` | Numero ticket usati per P, motivi `NO_AV_DECLARED/AV_UNRESOLVED/NO_OV/NO_FV/FV_UNRESOLVED/FV_AFTER_CORPUS/FV_EQ_OV/FV_LT_OV/IV_EQ_FV/IV_GT_FV`, 20 esempi IV/FV invalidi, P individuali, P maggiori di 1 e IV previste. |
| SZZ campione | `szz --limit=3` | Diff del fixing commit, parent, file del fix confrontati con file SZZ, edit, righe analizzate, blame, inducing commit e classi. Per il campione mirato STORM-3052/STORM-3132/STORM-2950 mostra anche source path e data del commit inducing. Puo' richiedere tempo. |
| Risoluzione globale classi SZZ | `szz-class-resolution` | Per tutti i ticket/classi SZZ che possono toccare il training, misura `EXACT_PATH`, `SOURCE_PATH`, `UNIQUE_SIMPLE_NAME`, `AMBIGUOUS` e `NOT_FOUND` per ogni release dell'intervallo buggy. I `NOT_FOUND` sono separati temporalmente e i `POST_INDUCING_NOT_FOUND` sono verificati con ancestry Git (`INDUCING_REACHABLE_FROM_RELEASE` / `INDUCING_NOT_REACHABLE_FROM_RELEASE`). E' la misura che giustifica il resolver storico usato dal dataset; puo' essere molto lento. |
| Labeling campione | `labels --limit=3` | Per ogni classe SZZ: AV/FV, IV scelta, fonte AV/Proportion, eventuale right-censoring e lista delle release nell'intervallo Jira. In chiusura riproduce il resolver finale: ancestry, path esatto/source path/simple name univoco e riepilogo CsvRow buggy. Puo' richiedere tempo. |
| Storia dei path SZZ | `path-history` | Segue all'indietro quattro path SZZ dal parent del fixing commit con rename detection Git. Mostra `SAME`, `RENAME`, `MOVE`, `ADD`, eventuale `COPY`, similarity score e presenza del path risolto in ciascuna delle 14 release training. Non modifica il labeling. |
| Metriche | `metrics` | CK, JGit, PMD e CsvRow della prima release training; commit Git raggiungibili, commit Java production, fixing commit, valori mancanti, anomalie e code smell. |
| CSV | `csv` | Colonne, righe, duplicati, valori vuoti/NaN, classi test nel CSV e distribuzione buggy. |

## A cosa serve ogni check

- `releases`, `tags`, `release-commit` e `release-commits` verificano le
  release Jira e il tag Git usato per ciascuna; il controllo completo rende
  visibili anche eventuali date commit-release incoerenti.
- `checkout` e `checkouts` verificano il comportamento reale che verra' usato
  durante la costruzione del CSV: tag scelto, HEAD e ripristino del clone.
- `scanner` controlla una release; `scanner-all` ripete il confronto tra tutti
  i `.java` e `isJavaProductionFile()` sulle 14 release training, provando il
  filtro anche su strutture storiche differenti.
- `class-names` quantifica quanto il simple class name sia affidabile nelle
  singole release: elenca ogni collisione, ma non trasforma mai il filename in
  un mapping automatico di classi.
- `tickets` identifica i bug ammessi. `versions` verifica che AV/FV Jira siano
  traducibili in release note; `opening-version` mostra `creationDate -> OV`.
- `commits` mostra i fixing commit e i ticket che non possono arrivare a SZZ
  perche' privi di commit associati.
- `proportion` calcola P totale e rende espliciti raw IV, indice arrotondato e
  release scelta per ogni stima.
- `szz` confronta file del fix e file per cui e' stata trovata una riga
  inducente. `labels` mostra direttamente ticket, classe, IV, fonte, FV e
  release marcate buggy, poi `release | classi buggy | totale classi | %`.
  Con `--limit=3` il riepilogo riguarda il campione analizzato.
- `szz-class-resolution` e' il controllo globale dell'impatto dei path
  storici. Segue l'ordine exact path, source path dal blame e simple name
  univoco. I risultati hanno mostrato che la quasi totalita' dei path ancora
  assenti appartiene a branch Git non raggiungibili dal tag target. Per questo
  il dataset usa la stessa sequenza solo dopo aver verificato che il commit
  inducing sia gia' avvenuto e sia raggiungibile dalla release. Ambigui e
  assenti restano volutamente non etichettati.
- `path-history` serve quando un path SZZ del fixing commit non coincide con
  il path della release da etichettare. Distingue un move Git confermato da un
  file non ancora esistente, da un tag fuori dalla catena first-parent e da una
  semplice copia, senza usare filename o candidati come mapping definitivo.
- `metrics` controlla la coerenza tra scanner, CK, PMD, JGit e `CsvRow`.
- `csv` valida il file finale e, con clone pulito, confronta le classi CSV con
  quelle del checkout della stessa release, evidenziando righe fantasma o
  classi mancanti.

## Sequenza consigliata

1. `releases`, `tags`, `release-commits` per validare le release e il mapping
   Git senza modificare il clone.
2. Dopo avere un clone pulito: `checkouts --limit=3`, poi `checkouts`.
3. `scanner`, `scanner-all`, `class-names`, `metrics` per verificare file, filtro test e metriche.
4. `tickets`, `versions`, `opening-version`, `commits`, `proportion` per verificare i dati del labeling.
5. `szz --limit=3`, `labels --limit=3`, `path-history` e, quando serve la misura completa, `szz-class-resolution` prima di passare al dataset completo.
6. Generare prima un dataset breve con `DatasetBuilder --release-limit=3`, poi controllarlo con `csv`; solo dopo generare tutte le 14 release e ripetere `csv`.

## Significato dei prefissi

- `[OK]`: controllo coerente con l'invariante verificata.
- `[WARN]`: informazione che richiede valutazione, ma non blocca il check.
- `[ERROR]`: condizione che impedisce quel controllo o viola l'invariante.

Un warning su una data, per esempio un `delta` negativo, non viene corretto
automaticamente: viene lasciato visibile per consentire di verificare la
relazione tra Jira e Git prima di decidere come trattarlo nel dataset.
