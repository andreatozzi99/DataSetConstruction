# Decisioni metodologiche e implementative

Registro delle scelte che influenzano il dataset Apache Storm. Serve per
discutere la Milestone 1 in modo riproducibile.

Stati: **Implementata** = gia' nel codice; **Approvata, da implementare** =
scelta confermata ma non ancora presente nel codice; **Aperta** = richiede una
decisione della consegna o del professore.

## Dataset e classi analizzate

### Unita' di osservazione

**Decisione — Implementata**

Ogni riga CSV corrisponde a una classe Java di produzione in una release. La
chiave logica e' `(release, class_path)`: la stessa classe in release diverse
genera righe diverse perche' metriche e bugginess cambiano nel tempo.

### Produzione contro test

**Decisione — Implementata**

Un file entra nella pipeline se termina con `.java` e il path normalizzato non
contiene una directory `test`. La regola e' centralizzata in
`JavaClassScanner.isJavaProductionFile()` e viene usata da scanner,
CommitManager e SZZ. I path CSV sono relativi alla radice del clone e usano
sempre `/`.

Motivazione: file di test non devono essere osservazioni del dataset ne'
classi buggy attribuite da un fixing commit. `scanner-all` confronta tutti i
file `.java` fisici con quelli ammessi dal filtro su tutte le release training.

### Codice generato

**Decisione — Aperta; comportamento attuale implementato**

I file Java sotto directory come `generated/` restano inclusi, se non sono
test. La consegna non ne richiede l'esclusione; percio' gli outlier PMD restano
visibili e non vengono rimossi per rendere migliori le statistiche.

Se il professore richiedera' solo codice scritto/manutenuto manualmente, il
filtro dovra' essere modificato esplicitamente e il dataset rigenerato.

## Release: corpus, date e cutoff

### Corpus congelato

**Decisione — Implementata**

Il corpus e' costituito esclusivamente dalle versioni di Apache Storm che Jira
marca come rilasciate e per cui Jira fornisce una data di release.

```text
Jira: released = true AND releaseDate valorizzata
```

Questa scelta segue l'indicazione del docente: **"la verita' sta su Jira, il
codice su Git"**. Jira e' quindi l'autorita' per decidere se una versione
appartiene al corpus e per la sua data; Git e' l'autorita' per il tag e il
codice da analizzare.

Con questa regola, una release ufficiale assente da Jira, oppure presente ma
non marcata `released`, non viene aggiunta manualmente. Per esempio `2.6.4` e
`2.8.0` possono essere release ufficiali Storm, ma non fanno parte del corpus
finche' Jira non le rende risolvibili con la regola sopra.

L'inventario corrente risultante e' di 41 release. Il check `versions` segnala
AV/FV non risolte nel corpus Jira: e' un dato diagnostico, non una richiesta di
aggiungere automaticamente quelle versioni.

### Associazione Jira-Git e data della release

**Decisione — Implementata**

`ReleaseManager` ordina le release per `releaseDate` Jira. Per ognuna,
`CheckoutManager` cerca in Git il tag con nome `releaseName` oppure
`v + releaseName`. Git fornisce il commit e il contenuto della release.

Il check `release-commits` mostra il delta fra la data Jira e la data del
commit puntato dal tag. Un delta negativo e' un warning da registrare, ma non
autorizza a sostituire autonomamente la data Jira con una data esterna.

### Training set

**Decisione — Implementata**

Il training set e' il primo 34% cronologico:

```text
ceil(numero_release * 0.34)
```

Con 41 release il training set e' composto da 14 release. `releases` deve
confermare questo conteggio a ogni nuova esecuzione, perche' Jira e' una fonte
live e il progetto potrebbe aggiornare le proprie versioni in futuro.

## Ticket, commit e SZZ

### Ticket bug ammessi

**Decisione — Implementata**

Sono candidati solo ticket:

```text
Project = STORM
Type = Bug
Status = Closed o Resolved
Resolution = Fixed
```

Un ticket non risolto non offre un fixing commit affidabile e non delimita un
intervallo di bugginess.

### Ticket esterni al corpus

**Decisione — Implementata**

Un ticket contribuisce a Proportion solo se IV, OV e FV sono tutte risolvibili
nelle release Jira del corpus. Una FV sconosciuta non viene mai sostituita con
l'ultima release disponibile.

Per il labeling sono distinti due casi:

- `FV_UNRESOLVED`: la FV non e' mappabile e non e' chiaramente successiva al
  corpus (per esempio `1.x`, `1.0.7`, `1.1.4`). Il ticket non produce label e
  non contribuisce a P.
- `FV_AFTER_CORPUS`: la FV ha una forma numerica valida ed e' maggiore della
  massima release Jira. Se esiste una IV valida, il ticket e' right-censored:
  le classi SZZ restano buggy da IV fino all'ultima release osservata. Il
  ticket non contribuisce comunque a P.

Motivazione: non si inventa una FV interna; la censura a destra e' ammessa solo
quando l'ordinamento temporale della FV futura e' dimostrabile.

### Fixing commit

**Decisione — Implementata**

Un fixing commit contiene una chiave completa `STORM-n` nel messaggio. Il
matching evita casi parziali come `STORM-1` dentro `STORM-123`. Il report
distingue correttamente associazioni `ticket -> commit` e hash Git distinti:
un commit puo' essere collegato a piu' ticket.

I ticket senza fixing commit sono esclusi dal labeling, perche' non possono
fornire classi da analizzare con SZZ.

### Ruolo di SZZ

**Decisione — Implementata**

SZZ parte dai fixing commit, confronta diff e parent, considera Java production,
ignora inserimenti puri e applica blame alle righe eliminate/sostituite. SZZ
identifica **quali classi** sono coinvolte; non sovrascrive IV se esiste una AV
valida su Jira.

I check `szz` e `labels` non scelgono piu' i primi ticket disponibili: cercano
un campione con intervallo `[IV,FV)` che interseca le 14 release training e con
almeno un fixing commit. Il report SZZ dichiara esplicitamente il parent su cui
JGit Blame opera e, per un fixing commit del campione, mostra file, numero di
riga della versione precedente, tipo `DELETE`/`REPLACE` e commit restituito
dal blame. Sono dati di osservazione, non una variante dell'algoritmo SZZ.

Il report `labels` ricerca in modo esplicito un caso AV, un caso con IV da
Proportion e un ticket con piu' fixing commit, quando presenti. Stampa IV, OV,
FV, fonte IV, classi SZZ e release marcate; il riepilogo finale separa classi
buggy e non-buggy per ciascuna release training. Questa selezione modifica
solo la leggibilita' del check, mai le label generate dal DatasetBuilder.

Quando una classe SZZ e' marcata in un intervallo training ma non compare nel
riepilogo, il check `labels` esegue una diagnostica separata `SZZ -> scanner
-> CSV`: confronta il path SZZ in modo letterale con i file production della
release, legge (senza modificarlo) l'eventuale CSV gia' scritto e mostra il
risultato che `DatasetBuilder` otterrebbe con `isBuggy(release, row.classPath)`.
Per un mismatch stampa soltanto candidati con stesso filename, simple class
name e package/classe. I candidati non sono normalizzati né usati per label,
rename handling o SZZ: la decisione su un eventuale mapping storico resta
esplicitamente separata e successiva alla diagnosi.

Il check `path-history` parte dal path nel **parent** del fixing commit e ne
segue la catena Git first-parent all'indietro con rename detection JGit. Per
ogni evento mostra old/new path, tipo e similarity score; per ogni release
training controlla anche presenza nel tree del tag e nello scanner. Un tag che
non appartiene alla catena tracciata e' `NOT_ON_TRACED_ANCESTRY`; un file
introdotto dopo il tag e' `NOT_YET_EXISTING`. Entrambi impediscono di inventare
un mapping. Un evento `COPY` viene esposto ma non seguito come rename/move,
perche' una copia non dimostra la medesima identita' storica del file.

Il check `szz` mostra, per ogni riga analizzata, source path e data del commit
inducing accanto a IV. Il blame sul parent del fix puo' seguire rename/move per
recuperare il source path storico. Questa evidenza non sostituisce IV, OV o FV:
serve esclusivamente a verificare se un path della classe puo' essere ritrovato
in un checkout piu' vecchio.

Il check `class-names` conta, in ogni release training, filename/simple class
name che compaiono una sola volta e nomi che collidono fra path production. Il
report elenca tutti i path per ogni collisione. Questa misura e' una verifica
del rischio: un filename, anche quando univoco in una release, non viene mai
usato come mapping definitivo per SZZ o labeling.

Il check `szz-class-resolution` misura globalmente il collegamento fra classi
SZZ e scanner nelle sole release training comprese nel loro intervallo buggy.
L'ordine osservato e' exact path, source path del blame, poi simple name se ha
una sola candidata. La stessa sequenza e' ora usata dal labeling finale, ma
solo dopo i controlli temporali e di ancestry descritti sotto. `AMBIGUOUS` e
`NOT_FOUND` non scelgono mai un candidato e non producono alcuna label.

**Evidenza osservata — da verificare prima di ogni modifica al labeling**

Nel primo run globale su 3774 coppie ticket/classe/release, `EXACT_PATH` ha
risolto il 45,97%, `SOURCE_PATH` l'1,59% e `UNIQUE_SIMPLE_NAME` il 13,54%; gli
`AMBIGUOUS` sono il 2,04%, mentre `NOT_FOUND` e' il 36,86%. Il problema non e'
uniforme: le release parallele 0.9.7 e 0.10.2 mostrano una quota NOT_FOUND piu'
alta rispetto alle release 1.0.x. Questa evidenza e' coerente sia con move di
directory/package sia con classi non ancora esistenti nella singola linea di
release, ma non prova da sola quale delle due cause valga per ogni caso.

Per questo il medesimo check separa ora ogni `NOT_FOUND` in `PRE_INDUCING`
(release precedente al piu' antico commit inducing della classe),
`POST_INDUCING_NOT_FOUND` e `NO_INDUCING_DATE`. La separazione usa la data del
committer del piu' antico inducing commit gia' trovato da SZZ e non tenta un
ulteriore matching.

I soli `POST_INDUCING_NOT_FOUND` ricevono inoltre una verifica di ancestry:
il report chiede se il commit del tag della release discende dal piu' antico
inducing commit. `INDUCING_NOT_REACHABLE_FROM_RELEASE` e' compatibile con una
linea/branch parallela; `INDUCING_REACHABLE_FROM_RELEASE` conferma invece che
la storia del commit e del tag e' connessa.

### Risoluzione storica delle classi SZZ

**Decisione — Implementata dopo la diagnostica globale**

L'intervallo Jira `[IV,FV)` stabilisce che un ticket puo' rendere buggy una
classe, ma non autorizza a trasferire quella classe indistintamente fra linee
Git divergenti di Storm. Per ogni coppia *classe SZZ / release* inclusa
nell'intervallo, il `DatasetBuilder` applica quindi questa regola conservativa:

```text
1. se releaseDate < data del piu' antico inducing commit: non marcare;
2. se nessun inducing commit della classe e' ancestor del tag della release:
   non marcare;
3. altrimenti cerca, in questo ordine:
   a. exact path SZZ;
   b. source path restituito dal blame con rename-following;
   c. simple filename/class name, soltanto se possiede una candidata unica;
4. se ci sono piu' candidate o nessuna candidata: non marcare.
```

Il controllo di ancestry e' fatto nel grafo Git: il tag della release deve
discendere da almeno un commit inducing della classe. Non basta dunque che il
ticket abbia un intervallo cronologico valido. Se un inducing commit e' su un
branch diverso, quel file non e' attribuito artificialmente al branch della
release target.

**Evidenza che ha motivato la scelta — osservata sul check
`szz-class-resolution`**

Su 3774 coppie ticket/classe/release training, 2306 sono state risolte dai tre
matching prudenti: 1735 exact path, 60 source path e 511 simple name univoco.
Dei 1391 `NOT_FOUND`, 703 erano `PRE_INDUCING`: il codice non era ancora stato
introdotto alla data della release. Dei restanti 688 `POST_INDUCING_NOT_FOUND`,
684 (99,42%) non sono raggiungibili dal commit del tag; pertanto sono coerenti
con release su branch paralleli e non con un semplice errore di nome. Solo 4
casi erano raggiungibili: tre coinvolgono
`TridentKafkaTopology.java` in un source path sotto `src/test` (fuori dal
dataset production), e uno `StormClientHandler.java` con uno spostamento fra
moduli `storm-netty` e `storm-core`.

Motivazione: un resolver storico piu' aggressivo potrebbe aumentare le label,
ma non sarebbe dimostrabile dai dati disponibili e rischierebbe falsi positivi.
La scelta privilegia la precisione: una classe non dimostrata nel tag production
resta `buggy=false` per quel ticket/release.

## IV, OV, FV, Proportion e label

**Decisione — Implementata**

- FV: prima Fix Version valida in ordine cronologico.
- OV: prima release non precedente alla `creationDate` del ticket.
- IV con AV: prima Affected Version valida.
- IV senza AV: stima con Proportion Total.

Nei report diagnostici, un ticket senza AV dichiarate e' `NO_AV_DECLARED`; un
ticket con AV dichiarate ma nessuna risolvibile nel corpus Jira e'
`AV_UNRESOLVED`. Se la `creationDate` e' successiva all'ultima release Jira,
l'OV e' `OV_AFTER_CORPUS`: e' una censura temporale osservabile, non un errore
della pipeline.

I casi `FV_LE_OV` sono mantenuti e stampati in un campione diagnostico. Non
vengono "risolti" cambiando OV, ordinamento o formula di Proportion: prima si
ispezionano i ticket per distinguere una scelta Jira spiegabile da un errore
metodologico.

Il check `proportion` produce inoltre una diagnostica `major.minor` delle linee
di release e, per i casi `IV > OV` che restano validi per P, controlla
l'ancestry Git dei tag. Queste informazioni sono soltanto descrittive: non
modificano indici cronologici, formula P, esclusioni, IV stimata, SZZ o
labeling.

**Estensione diagnostica — Implementata**

L'ancestry Git viene osservata per tutti i ticket effettivamente usati per P.
Per ogni tripla IV/OV/FV il report salva le quattro relazioni fra commit
(`FV` discende da `IV`, `FV` discende da `OV`, `OV` discende da `IV`, `IV`
discende da `OV`) e la classifica in una delle categorie
`LINEAR_IV_OV_FV`, `LINEAR_OV_IV_FV`, `IV_FV_CONNECTED_OV_OUTSIDE`,
`OV_FV_CONNECTED_IV_OUTSIDE`, `NO_SINGLE_LINEAGE`.

Per la sola categoria `LINEAR_IV_OV_FV` viene anche contata la distanza fra i
tag sul percorso Git e confrontata, a titolo informativo, con la distanza fra
gli indici globali Jira. Questa verifica non introduce una seconda formula P
e non sostituisce la sequenza cronologica Jira: serve a rendere esplicito al
professore quando la numerazione globale attraversa release parallele.

```text
P = (FV - IV) / (FV - OV)
raw_IV = FV - P_total * (FV - OV)
```

P Total e' la media dei P validi. `P > 1` e' ammesso; si scartano solo dati
mancanti o temporalmente impossibili, come `FV <= OV` o `IV >= FV`. Il valore
raw viene arrotondato e mappato a una release valida.

Tutte le classi partono con `buggy=false`. Una classe SZZ e' buggy in:

```text
IV <= release < FV
```

FV e' esclusa perche' contiene il fix. Se piu' ticket coinvolgono una classe,
la label resta true finche' almeno un intervallo e' attivo.

## Metriche, validazione e riproducibilita'

**Decisione — Implementata**

CK, PMD e JGit lavorano sulle classi dello scanner. `metrics` controlla la
corrispondenza fra file Java, CK, PMD e CsvRow. La storia JGit usa gli stessi
fixing commit distinti del DatasetBuilder e riporta commit raggiungibili,
commit Java production, ClassChanges e ClassChanges FIX.

Ogni check salva l'ultimo output in `CheckResults/NOME_CHECK.txt` e usa
`[OK]`, `[WARN]`, `[ERROR]`. I check con checkout si fermano se il clone e'
sporco, per non sovrascrivere file locali.

## Prossimo passo obbligato

Prima di rigenerare Proportion o dataset, va validato il corpus Jira corrente
con `releases`, `versions` e `release-commits`. Non occorre sostituire
`ReleaseManager` con un manifest esterno. Poi vanno ripetuti:

```text
releases -> tags -> release-commits -> checkouts -> scanner-all
-> versions -> opening-version -> commits -> proportion -> szz -> labels -> csv
```
