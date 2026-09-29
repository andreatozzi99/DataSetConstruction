from pathlib import Path

md = r"""# MILESTONE 1 – DATASET CREATION
## Apache STORM – guida alla pipeline, ai check, a SZZ, Proportion e labeling

Documento di lavoro aggiornato con i controlli eseguiti, le anomalie già analizzate e la nuova diagnostica sulla **lineage delle release**.

> **Obiettivo della Milestone**  
> Produrre un CSV con una riga per ogni file/classe Java di ogni release analizzata, contenente circa 20 feature, `NSmells` e la label finale `buggy` (`yes/no`). La consegna richiede inoltre di ignorare l’ultimo 66% delle release e di usare **SZZ + Proportion Total** per il labeling.

---

# 1. Visione generale della pipeline

```text
                  ┌── CK ──────────────┐
                  │                    │
Release → Checkout → Scanner → Metriche ├→ CsvRow → CSV
                  │                    │
                  ├── PMD ─────────────┤
                  │                    │
                  └── JGit ────────────┘

Jira → TicketManager ─┐
                      ├→ BuggyLabeler ─→ buggy
Git  → CommitManager ─┘
```

La pipeline ha due flussi distinti che si incontrano alla fine:

- **flusso delle feature**: release → checkout → classi Java → metriche → `CsvRow`;
- **flusso del labeling**: ticket Jira + storia Git → SZZ/Proportion → classe buggy o non buggy.

La separazione delle fonti è fondamentale:

- **Jira** è la fonte per release, date, ticket, Affected Version e Fix Version;
- **Git** è la fonte per codice, tag, commit, diff, blame e storia delle modifiche.

> **Regola di coerenza**  
> Non si devono “riparare” i metadati Jira importando automaticamente versioni dai tag Git. Se una AV/FV non è risolvibile nel corpus Jira, va segnalata come non risolta e gestita esplicitamente.

---

# 2. Come vengono costruite le feature

## 2.1 CK – metriche statiche della release

```text
checkout 0.9.0.1
       ↓
CK
       ↓
metriche 0.9.0.1

checkout 0.9.1-incubating
       ↓
CK
       ↓
metriche 0.9.1-incubating

...
```

CK lavora sullo **snapshot della singola release**.  
Le metriche vengono quindi ricalcolate dopo ogni checkout: non descrivono la storia, ma la struttura del codice così come esiste in quella release.

Metriche CK attualmente considerate:

- `LOC` – Lines of Code;
- `WMC` – Weighted Methods per Class;
- `CBO` – Coupling Between Objects;
- `RFC` – Response For a Class;
- `LCOM` – Lack of Cohesion of Methods;
- `DIT` – Depth of Inheritance Tree;
- `NOC` – Number of Children;
- `FANIN` / `FANOUT` – dipendenze entranti e uscenti.

---

## 2.2 JGit – metriche storiche

```text
              STORIA GIT
                  │
         ┌────────┴────────┐
         │                 │
      commit 1          commit 2 ...
         │                 │
       diff              diff
         │                 │
    ┌────┼────┐       ┌────┼────┐
  A.java B.java       A.java C.java
```

JGit ricostruisce come una classe è cambiata nel tempo fino alla release in esame.

Feature storiche attualmente implementate:

- `commitCount` – numero di commit che hanno toccato la classe;
- `fixCommitCount` – numero di fixing commit che l’hanno toccata;
- `churn` – quantità complessiva di codice aggiunto/rimosso;
- `averageChangeSetSize` – numero medio di file modificati insieme alla classe;
- `distinctAuthors` – sviluppatori distinti;
- `daysSinceLastChange` – giorni dall’ultima modifica;
- `changeFrequency` – frequenza delle modifiche;
- `changeCountLast90Days` – modifiche recenti;
- `modificationIntervalsStdDev` – variabilità degli intervalli tra modifiche;
- `authorChangeEntropy` – dispersione delle modifiche tra gli autori.

Altre metriche discusse negli appunti o potenzialmente utili, ma da distinguere da quelle effettivamente implementate, includono:

- tempo dall’ultimo bug fix;
- percentuale di modifiche dovute a bug fix;
- numero di moduli toccati;
- rapporto added/deleted;
- numero di volte in cui una classe viene modificata insieme a classi già buggy.

Queste non vanno presentate come feature del CSV se non sono realmente implementate.

---

## 2.3 PMD – NSmells

PMD applica un ruleset ai file Java e produce il numero di violazioni per file.  
Nel dataset questo valore viene aggregato come:

```text
NSmells
```

### Code smell nelle classi `generated/`

Durante il calcolo di `NSmells` con PMD è emerso che molte delle classi con il numero più alto di smell appartengono a cartelle `generated/`.

Esempio:

```text
storm-core/src/jvm/backtype/storm/generated/Nimbus.java -> 1204 smell
```

Nella prima release:

```text
NSmells medio ≈ 9.44
Nimbus.java   = 1204
```

Quindi `Nimbus.java` è un outlier enorme.

Questo **non significa necessariamente che PMD stia sbagliando**.  
PMD sta semplicemente applicando le stesse regole a codice molto grande e ripetitivo, spesso generato automaticamente.

Il problema è la **rappresentatività**:

- se la consegna richiede tutte le classi Java, il codice generated va mantenuto;
- se si volesse analizzare solo codice realmente scritto e mantenuto dagli sviluppatori, il codice generated potrebbe essere escluso.

Per ora la scelta prudente è **non eliminarlo automaticamente** e documentare l’anomalia.

---

# 3. Ticket Jira e dati per il labeling

La consegna richiede di partire assumendo tutte le classi non buggy e cercare ticket che soddisfano:

```text
Type = Bug
Status = Closed OR Resolved
Resolution = Fixed
```

## Risultati attuali TicketManager

| Controllo | Risultato |
|---|---:|
| Issue totali progetto | 4100 |
| Closed/Resolved di ogni tipo | 4049 |
| Bug + Closed/Resolved + Fixed | 1193 |
| Type = Bug | 1193/1193 |
| Resolution = Fixed | 1193/1193 |
| Closed | 105 |
| Resolved | 1088 |
| creationDate nulle | 0 |
| resolutionDate nulle | 0 |

Distribuzione AV/FV:

```text
AV + FV presenti = 730
solo AV          = 15
solo FV          = 416
né AV né FV      = 32

730 + 15 + 416 + 32 = 1193 ticket
```

---

# 4. Affected Version, Opening Version e Fix Version

## 4.1 IV – Injected Version

`IV` è la prima release nella quale il bug è considerato presente.

Nel flusso attuale:

- se esistono Affected Version valide, viene usata la **prima AV cronologicamente valida**;
- se AV manca, IV viene stimata con **Proportion Total**.

---

## 4.2 OV – Opening Version

Dalle indicazioni del corso:

> OV è la prima release con cui si apre il bug.

Se la creation del ticket cade tra R14 e R15:

```text
R14 -------- creation ticket -------- R15
                                      ↑
                                      OV
```

La regola implementata è quindi:

```text
OV = prima release con releaseDate >= creationDate
```

---

## 4.3 FV – Fixed Version

`FV` è la prima release che contiene la correzione.

Concettualmente:

```text
fix commit -------- R18
                    ↑
                    FV
```

Quando Jira fornisce una Fix Version valida e risolvibile nel corpus, quella resta la fonte autorevole.

---

# 5. Release Jira, tag Git e checkout

Per ogni release Jira si cerca il tag Git corrispondente e il commit puntato dal tag.

```text
Release Jira
    ↓
Tag Git
    ↓
Commit Git
    ↓
Checkout
```

Il checkout di un tag porta normalmente Git in:

```text
detached HEAD
```

Non è un errore.

Il check deve verificare che:

- il tag sia risolvibile;
- HEAD corrisponda al commit atteso;
- il repository venga ripulito al termine.

### Anomalie temporali già osservate

```text
1.2.1
commitDate  = 2018-02-16
releaseDate = 2018-02-15
delta       = -1
→ warning lieve
```

```text
2.7.0
commitDate  = 2024-10-11
releaseDate = 2024-09-03
delta       = -38
→ warning da investigare
```

La data Jira **non deve essere sovrascritta automaticamente** con quella Git.

---

# 6. SZZ spiegato passo per passo

SZZ non parte direttamente dal ticket.

Prima bisogna trovare il **fixing commit**.  
Poi si guarda cosa il fixing commit ha rimosso o modificato e si usa `blame` sul codice precedente alla fix per risalire ai commit che avevano introdotto quelle righe.

```text
Ticket Jira
    ↓
Fixing commit F
    ↓
Parent(F) = commit immediatamente precedente
    ↓
Diff Parent(F) → F
    ↓
Righe vecchie rimosse/modificate
    ↓
Blame delle righe nel Parent(F)
    ↓
Bug-inducing commit candidati
    ↓
File/classe candidata buggy
```

---

## 6.1 Che cos’è il parent del fixing commit

Supponiamo:

```text
A → B → C → D
            ↑
            D = fixing commit

parent(D) = C
```

- `C` rappresenta il codice immediatamente prima della correzione;
- `D` rappresenta il codice dopo la fix.

Per capire cosa è stato corretto, il confronto corretto è quindi:

```text
C → D
```

---

## 6.2 `diff`: cosa fa e perché serve

Il `diff` confronta due revisioni e mostra le righe:

- aggiunte;
- rimosse;
- sostituite.

Concettualmente:

```bash
git diff <parent> <fixing-commit>
```

Esempio:

```diff
- if (value > 0) {
-     execute();
- }

+ if (value >= 0) {
+     execute();
+ }
```

Per SZZ interessano soprattutto le **righe vecchie**, cioè quelle che esistevano prima della fix.

Una pure INSERT:

```diff
+ if (x == null) {
+     return;
+ }
```

non contiene una riga vecchia da attribuire a un commit precedente.

Per questo un SZZ classico non usa una pure INSERT come riga inducing.

---

## 6.3 `blame`: cosa fa e perché serve

`blame` associa ogni riga di un file a un commit precedente.

Concettualmente:

```bash
git blame <parent-del-fix> -- <file>
```

Va eseguito sul **parent del fixing commit**, perché nel fixing commit la riga vecchia potrebbe essere già stata eliminata.

Se una riga rimossa dalla fix viene attribuita dal blame al commit:

```text
ABC123
```

allora `ABC123` diventa un possibile **bug-inducing commit**.

È una euristica: non è una prova matematica che quel commit abbia creato il bug.

---

## 6.4 Esempio completo SZZ

```text
Fixing commit F
  modifica A.java

Parent P
  contiene ancora le righe prima della correzione

Diff P → F
  trova 4 righe vecchie rimosse/modificate

Blame su P
  3 righe → commit X
  1 riga  → commit Y

Risultato SZZ
  A.java → {X, Y}
```

Il risultato utile per il dataset è che `A.java` è una classe candidata buggy.

Successivamente IV/FV stabiliscono **in quali release** va marcata buggy.

---

# 7. Finding dei fixing commit

Il `CommitManager` cerca nel repository i commit associati alle chiavi Jira.

Il matching deve essere esatto:

```text
STORM-297
```

non deve essere confuso con:

```text
STORM-2970
```

Risultati attuali:

```text
1193 ticket analizzati
1058 con almeno un fixing commit
135 senza fixing commit
2636 associazioni ticket → fixing commit
media 2.2096 commit/ticket
massimo 29 commit per un ticket
```

Circa l’11.3% dei ticket non può entrare in SZZ perché manca il fixing commit associato.

Questo non significa necessariamente che Jira sia errato: significa che con il criterio di matching Git usato manca il punto di partenza necessario a SZZ.

> **Nota sui conteggi**  
> Conviene distinguere sempre:
>
> - numero di associazioni ticket → commit;
> - numero di hash di fixing commit distinti.
>
> Lo stesso commit può essere associato a più ticket.

---

# 8. Proportion Total

Quando AV non è disponibile, la consegna richiede di usare **Proportion Total** per stimare IV.

Formula:

```text
P = (FV - IV) / (FV - OV)
```

Si calcola `P` sui ticket dove IV, OV e FV sono utilizzabili.

Poi:

```text
P_total = media dei P validi
```

Per un ticket senza AV:

```text
IVp = FV - P_total × (FV - OV)
```

---

## 8.1 P > 1 è valido

`P` **non è una probabilità**.

Può essere maggiore di 1.

Esempio:

```text
IV = 36
OV = 39
FV = 40

P = (40 - 36) / (40 - 39)
  = 4
```

Scartare `P > 1` era un errore e non va reintrodotto.

---

## 8.2 Risultato attuale del check

```text
Ticket usati per P: 183

P min      = 0.2500
P max      = 12.0000
P mediana  = 1.5000
P_total    = 2.2272

P > 1      = 137
P == 0     = 0
P negativi = 0
```

---

## 8.3 Perché 352 ticket hanno FV <= OV

Risultato validato:

```text
1193 ticket
1190 con OV
3 OV_AFTER_CORPUS
0 NO_OV veri

352 FV <= OV
 ├─ 343 FV == OV
 └─   9 FV < OV
```

### `FV == OV`

I 343 casi sono plausibili.

```text
ticket creation
      ↓
fix commit
      ↓
stessa release successiva
      ↓
OV = FV
```

In questo caso:

```text
FV - OV = 0
```

quindi la formula di P avrebbe divisione per zero.

Il ticket va escluso dal campione di Proportion.

### `FV < OV`

I 9 casi sono incompatibili con il modello lineare usato dalla formula.

Vanno:

- esclusi;
- documentati;
- **non corretti artificialmente**.

---

## 8.4 IV >= FV

Il vecchio gruppo di 167 casi è stato separato correttamente:

```text
IV_EQ_FV = 144
IV_GT_FV = 23
totale   = 167
```

### `IV_EQ_FV`

Esempio:

```text
STORM-3510
IV = 2.2.0
FV = 2.2.0
OV = 2.1.0
```

Questi casi non sono utili come campione per imparare la distanza di introduzione del bug.

### `IV_GT_FV`

Esempio:

```text
STORM-2903
IV = 2.0.0
FV = 1.2.0
OV = 1.0.6
```

L’ordine globale delle release rende IV successiva a FV.

Questi casi sono sospetti e hanno portato alla nuova analisi sulla lineage.

---

# 9. Nuova scoperta: l’indice cronologico globale non è necessariamente una buona distanza per Proportion

Questa è la nuova parte più importante.

Fino ad ora le release Jira sono state ordinate cronologicamente e numerate:

```text
R1
R2
R3
...
```

Questo indice globale è utile per:

- ordinare il dataset;
- scegliere il primo 34% delle release;
- sapere in quale ordine temporale sono state pubblicate;
- scorrere release dopo release durante la generazione del CSV.

Il problema nasce quando lo stesso indice viene usato come **misura di distanza** dentro:

```text
P = (FV - IV) / (FV - OV)
```

STORM ha avuto più linee di sviluppo contemporanee:

```text
0.9.x
0.10.x
1.0.x
1.1.x
1.2.x
2.x
...
```

Per questo l’ordine cronologico globale può interleavare release che non appartengono allo stesso percorso evolutivo.

Esempio reale:

```text
STORM-2607

OV = 1.0.4
IV = 1.1.1
FV = 1.1.2
```

Con gli indici globali:

```text
OV = 17
IV = 18
FV = 21
```

sembra quindi:

```text
IV > OV
```

Ma Git mostra che:

```text
FV descendant of IV = true
FV descendant of OV = false
IV descendant of OV = false
```

Quindi `1.1.1 → 1.1.2` appartiene a una catena Git coerente, mentre `1.0.4` è fuori da quella catena.

Questo significa che sottrarre semplicemente:

```text
FV_index - OV_index
```

può contare release del progetto che **non appartengono al percorso reale di sviluppo del bug**.

---

# 10. Diagnostica `major.minor`: utile ma non sufficiente

Per capire il problema è stata fatta una prima classificazione delle release tramite:

```text
major.minor
```

Esempi:

```text
1.0.4 -> linea 1.0
1.1.1 -> linea 1.1
1.2.0 -> linea 1.2
2.6.3 -> linea 2.6
```

Risultati sui 183 ticket usati per P:

```text
SAME_LINE_IV_OV_FV               = 35
IV_FV_SAME_LINE_OV_DIFFERENT     = 36
IV_OV_SAME_LINE_FV_DIFFERENT     = 66
OV_FV_SAME_LINE_IV_DIFFERENT     = 5
ALL_DIFFERENT_LINES              = 41
```

Totale:

```text
35 + 36 + 66 + 5 + 41 = 183
```

Solo:

```text
35 / 183 ≈ 19.1%
```

dei ticket ha IV, OV e FV sulla stessa `major.minor`.

Quindi circa:

```text
148 / 183 ≈ 80.9%
```

attraversa almeno due `major.minor`.

### Attenzione

Questo **non dimostra** automaticamente che l’80.9% attraversi branch Git indipendenti.

Per esempio:

```text
2.3.0 → 2.4.0 → 2.5.0
```

sono tre `major.minor` diverse ma possono essere perfettamente parte della stessa evoluzione del progetto.

Quindi `major.minor` è una diagnostica utile, ma **non è una vera lineage**.

---

# 11. Ancestry Git: la diagnostica più significativa

Per i 6 ticket che risultavano:

```text
IV_GT_OV_USED_FOR_P
```

è stata controllata la discendenza Git reale fra i tag.

Risultato:

## STORM-2607

```text
IV = 1.1.1
OV = 1.0.4
FV = 1.1.2

FV descendant of IV = true
FV descendant of OV = false
IV descendant of OV = false
```

Interpretazione:

```text
IV → FV
```

è una relazione Git valida.

OV è fuori dalla loro catena.

---

## STORM-2343

```text
IV = 1.1.0
OV = 1.0.3
FV = 1.1.1

FV descendant of IV = true
FV descendant of OV = false
IV descendant of OV = false
```

Anche qui:

```text
IV → FV
```

è coerente, mentre OV appartiene a un’altra storia.

---

## STORM-1761

```text
IV = 1.0.1
OV = 0.10.1
FV = 1.0.2

FV descendant of IV = true
FV descendant of OV = false
IV descendant of OV = false
```

Stesso pattern.

---

## STORM-1363

```text
IV = 0.10.1
OV = 1.0.0
FV = 1.1.0

FV descendant of IV = false
FV descendant of OV = true
IV descendant of OV = false
```

Qui la situazione è diversa:

```text
OV → FV
```

è coerente, ma IV appartiene a un’altra storia.

---

## STORM-584

```text
IV = 0.9.6
OV = 0.9.4
FV = 1.0.0

FV descendant of IV = false
FV descendant of OV = false
IV descendant of OV = true
```

Qui:

```text
OV → IV
```

è coerente, ma FV non risulta discendente da quella catena.

---

## STORM-130

```text
IV = 0.9.2-incubating
OV = 0.9.1-incubating
FV = 0.9.5

FV descendant of IV = true
FV descendant of OV = true
IV descendant of OV = true
```

Qui abbiamo una catena Git completa:

```text
OV → IV → FV
```

Questo è importante perché mostra che `IV > OV` non è sempre un artefatto di branch parallele.

In questo caso Jira indica effettivamente una Affected Version successiva alla Opening Version.

---

# 12. Cosa abbiamo imparato sulla Proportion

La nuova conclusione è:

> **L’indice cronologico globale è sicuramente utile per ordinare le release, ma non è ancora dimostrato che sia anche la misura migliore della distanza tra IV, OV e FV da usare nella Proportion.**

Il motivo è che:

```text
FV - IV
FV - OV
```

dovrebbero rappresentare una distanza in release significativa rispetto al ciclo di vita del bug.

Se tra due release vengono contate versioni pubblicate su branch parallele e non appartenenti alla stessa ancestry, la distanza globale può risultare distorta.

### Conseguenza pratica

Non bisogna ancora:

- escludere automaticamente i 6 `IV_GT_OV`;
- cambiare la formula;
- cambiare `P_total`;
- usare `major.minor` come sostituto della lineage;
- inventare un nuovo ordinamento.

Il passo corretto è verificare l’**ancestry Git su tutti i 183 ticket usati per P**.


---

# 14. Regole di labeling buggy

Tutte le classi partono da:

```text
buggy = false
```

Una classe può essere marcata buggy solo se:

1. SZZ la collega a un ticket;
2. l’intervallo di release è determinabile.

Caso normale:

```text
IV nota + FV nota
→ buggy in [IV, FV)
```

Quindi:

```text
IV inclusa
FV esclusa
```

Esempio:

```text
IV = R10
FV = R13
```

buggy in:

```text
R10
R11
R12
```

non buggy da:

```text
R13
```

---

## 14.1 AV presente

```text
IV = earliest valid Affected Version
```

---

## 14.2 AV assente

```text
IV = stima tramite Proportion Total
```

---

## 14.3 FV non risolta

Non bisogna assegnare automaticamente:

```text
FV = ultima release del corpus
```

perché falserebbe:

- labeling;
- calcolo di P;
- durata del bug.

I casi realmente successivi al corpus possono essere trattati come right-censored solo se è giustificabile che la fix sia oltre la finestra osservata.

---

# 16. Risultati già validati sulle classi

Scanner Java sulle 14 release training:

```text
0.9.0.1           449 production
0.9.1-incubating  450 production
0.9.2-incubating  525 production
0.9.3             605 production
0.9.4             606 production
0.9.5             606 production
0.10.0            807 production
0.9.6             607 production
1.0.0            1261 production
0.10.1            807 production
1.0.1            1264 production
1.0.2            1273 production
0.9.7             607 production
0.10.2            807 production
```

Il filtro è stato corretto anche per percorsi tipo:

```text
test/jvm
```

e non soltanto:

```text
src/test
```

Check metriche prima release:

```text
Input Java = 449
CK         = 449
PMD        = 449
CsvRow     = 449

senza CK   = 0
senza PMD  = 0
```

---

# 17. Mapping AV/FV

Ultimo risultato noto:

```text
AV dichiarate:     1170
AV risolte:        1117
AV non risolte:      53

FV dichiarate:     1782
FV risolte:        1746
FV non risolte:      36
```

Esempio:

```text
STORM-4154
AV = 2.7.0 -> risolta
FV = 2.8.0 -> non risolta nel corpus Jira
```

Non bisogna aggiungere `2.8.0` da Git solo perché esiste un tag.

---

# 18. Opening Version

Risultato validato:

```text
Ticket analizzati:        1193
Ticket con OV:            1190
OV_AFTER_CORPUS:             3
NO_OV:                       0
```

Gli unici 3 senza OV sono ticket creati dopo l’ultima release Jira osservata.


---


# 23. Schema mentale finale

```text
JIRA
 |
 |-- release ------------------------------+
 |                                         |
 |-- ticket Bug/Fixed                      |
 |     |                                   |
 |     |-- AV -> IV se disponibile         |
 |     |-- creationDate -> OV              |
 |     |-- FV                              |
 |     |                                   |
 |     +-> Proportion se AV manca          |
 |                                         |
 +-----------------------------------------+
                                           |
GIT                                        |
 |                                         |
 |-- tag release -> checkout               |
 |                                         |
 |-- fixing commit                         |
 |     |
 |     +-> parent
 |           |
 |           +-> diff
 |                 |
 |                 +-> righe vecchie
 |                       |
 |                       +-> blame
 |                             |
 |                             +-> inducing commit
 |                                   |
 |                                   +-> classe candidata buggy
 |
 |-- ancestry tag/release
 |     |
 |     +-> verifica reale della lineage
 |           |
 |           +-> confronto con distanza globale usata in P
 |
 +-> metriche storiche
                                           |
                                           v
                             LABELING PER RELEASE
                                           |
                                           v
                                   CSV FINALE
```

---