## Scopo di questo documento

Questo documento descrive un problema metodologico emerso durante il controllo della **Proportion Total** per Apache STORM.

Il problema non riguarda la formula in sé:

```text
P = (FV - IV) / (FV - OV)
```

ma il significato concreto delle differenze:

```text
FV - IV
FV - OV
```

Nel progetto, queste differenze sono state inizialmente calcolate sottraendo gli **indici cronologici globali delle release Jira**.

Questa scelta è naturale per:

- ordinare cronologicamente le release;
- scegliere il primo 34% delle release richiesto dalla Milestone;
- eseguire checkout e metriche release per release;
- costruire il dataset nel corretto ordine temporale.

Il problema è che Apache STORM ha avuto diverse **linee di release parallele**.  
Di conseguenza, due release consecutive nell'ordine temporale globale non sono necessariamente consecutive nella stessa linea evolutiva del codice.

---

# 1. Definizioni

Nel calcolo della Proportion vengono usate tre versioni:

```text
IV = Injected Version
OV = Opening Version
FV = Fixed Version
```

Nel nostro progetto:

- **IV**: prima Affected Version valida, se disponibile;
- **OV**: prima release con data >= creationDate del ticket;
- **FV**: Fix Version Jira valida.

La formula è:

```text
P = (FV - IV) / (FV - OV)
```

L'interpretazione intuitiva è che P rappresenti quanto indietro, rispetto all'apertura del ticket, si estende il periodo durante il quale il bug era già presente.

---

# 2. Approccio iniziale: indice cronologico globale

Le release Jira sono state ordinate per data e numerate:

```text
15  1.0.3
16  1.1.0
17  1.0.4
18  1.1.1
19  1.0.5
20  1.0.6
21  1.1.2
...
```

Questo ordine è corretto dal punto di vista temporale: indica semplicemente quando ogni release è stata pubblicata.

Il primo calcolo di Proportion utilizzava quindi:

```text
distanza(FV, IV) = index(FV) - index(IV)
distanza(FV, OV) = index(FV) - index(OV)
```

Il problema è che così vengono contate anche release appartenenti ad altre linee.

---

# 3. Esempio reale: STORM-2607

Il ticket:

```text
STORM-2607
```

ha:

```text
IV = 1.1.1
OV = 1.0.4
FV = 1.1.2
```

Con gli indici globali:

```text
OV = 17
IV = 18
FV = 21
```

Il calcolo corrente produce:

```text
P = (21 - 18) / (21 - 17)
  = 3 / 4
  = 0.75
```

Ma l'analisi della ancestry Git mostra:

```text
FV descendant of IV = true
FV descendant of OV = false
IV descendant of OV = false
```

Quindi esiste una relazione reale:

```text
1.1.1 -> 1.1.2
```

mentre:

```text
1.0.4
```

non appartiene alla stessa catena Git.

L'indice globale sta quindi misurando anche il passaggio attraverso release appartenenti a una diversa linea di sviluppo.

---

# 4. Perché il problema è importante

Il significato della formula dovrebbe essere legato alla **distanza in release**.

Se però la distanza viene calcolata con un indice globale, questa distanza può significare:

> quante release del progetto sono state pubblicate nel frattempo

anziché:

> quante release sono trascorse lungo il percorso evolutivo pertinente al bug

Queste due quantità non coincidono necessariamente.

---

# 5. Diagnostica `major.minor`

Come primo controllo, le release sono state raggruppate usando il prefisso:

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

Sui 183 ticket inizialmente usati per P:

```text
SAME_LINE_IV_OV_FV               = 35
IV_FV_SAME_LINE_OV_DIFFERENT     = 36
IV_OV_SAME_LINE_FV_DIFFERENT     = 66
OV_FV_SAME_LINE_IV_DIFFERENT     = 5
ALL_DIFFERENT_LINES              = 41
```

Quindi solo:

```text
35 / 183 = 19.1%
```

dei ticket ha IV, OV e FV nella stessa `major.minor`.

Circa:

```text
148 / 183 = 80.9%
```

dei ticket attraversa almeno due gruppi `major.minor`.

## Limite di questa diagnostica

Questo risultato **non dimostra** che i ticket attraversino davvero branch indipendenti.

Per esempio:

```text
2.3.0 -> 2.4.0 -> 2.5.0
```

sono tre `major.minor` diverse, ma possono appartenere alla stessa evoluzione del progetto.

Quindi `major.minor` è solo un indicatore descrittivo, non una vera definizione di lineage.

---

# 6. Diagnostica basata sulla ancestry Git

Per capire la relazione reale tra IV, OV e FV, è stato controllato il grafo Git.

Per ogni ticket sono state verificate relazioni del tipo:

```text
FV descendant of IV
FV descendant of OV
OV descendant of IV
IV descendant of OV
```

I 183 ticket usati per Proportion sono stati classificati così:

```text
LINEAR_IV_OV_FV                   50 / 183  = 27.32%
LINEAR_OV_IV_FV                    1 / 183  =  0.55%
IV_FV_CONNECTED_OV_OUTSIDE        45 / 183  = 24.59%
OV_FV_CONNECTED_IV_OUTSIDE         5 / 183  =  2.73%
NO_SINGLE_LINEAGE                 82 / 183  = 44.81%
```

---

# 7. Significato delle categorie

## LINEAR_IV_OV_FV

Esiste una catena Git coerente:

```text
IV -> OV -> FV
```

Questa è la situazione più vicina al modello intuitivo della Proportion.

Esempio:

```text
STORM-4055
IV = 2.6.1
OV = 2.6.3
FV = 2.7.0
```

---

## LINEAR_OV_IV_FV

Esiste la catena:

```text
OV -> IV -> FV
```

Nel dataset corrente esiste un solo caso:

```text
STORM-130
IV = 0.9.2-incubating
OV = 0.9.1-incubating
FV = 0.9.5
```

Qui Git conferma:

```text
OV -> IV -> FV
```

Questo significa che, secondo Jira, la prima Affected Version è successiva all'apertura del ticket.

Quindi un caso `IV > OV` non è necessariamente dovuto a branch parallele: può esistere anche su una singola lineage.

---

## IV_FV_CONNECTED_OV_OUTSIDE

Esiste:

```text
IV -> FV
```

ma OV non appartiene alla stessa catena.

Esempio:

```text
STORM-2607
IV = 1.1.1
OV = 1.0.4
FV = 1.1.2
```

Questo è uno dei segnali più chiari che l'ordine globale delle release può introdurre distorsioni.

---

## OV_FV_CONNECTED_IV_OUTSIDE

Esiste:

```text
OV -> FV
```

ma IV è fuori dalla catena.

Esempio:

```text
STORM-1363
IV = 0.10.1
OV = 1.0.0
FV = 1.1.0
```

---

## NO_SINGLE_LINEAGE

Non esiste una singola catena Git che contenga coerentemente IV, OV e FV.

Questa categoria contiene:

```text
82 / 183 = 44.81%
```

dei ticket inizialmente usati per P.

È la categoria più numerosa.

---

# 8. Un secondo problema: ancestry Git non equivale automaticamente a successione delle release

La ancestry Git è più precisa dell'indice cronologico globale per capire la storia del codice, ma non può essere usata automaticamente come sostituto.

Esempio:

```text
STORM-4052
IV = 2.6.1
OV = 2.6.2
FV = 2.6.3
```

Semanticamente le versioni sembrano una successione naturale.

Tuttavia il check Git restituisce:

```text
FV descendant of IV = true
FV descendant of OV = false
OV descendant of IV = true
```

e quindi il ticket viene classificato:

```text
NO_SINGLE_LINEAGE
```

Questo può succedere perché le release vengono create tramite:

- branch separati;
- merge non lineari;
- cherry-pick;
- tag su commit che non formano una catena semplice.

Quindi:

```text
successione delle release
```

e:

```text
ancestry Git pura
```

non sono necessariamente equivalenti.

---

# 9. Confronto tra distanza globale e distanza lungo la catena Git

Per i 50 ticket classificati:

```text
LINEAR_IV_OV_FV
```

è stato possibile confrontare:

```text
distanza globale
```

con:

```text
distanza lungo la catena Git
```

Risultato:

```text
40 / 50
```

ticket hanno almeno una distanza diversa.

Questo è un risultato molto forte.

---

# 10. Esempi di distorsione

## STORM-2666

```text
IV = 1.0.0
OV = 1.1.1
FV = 1.1.2
```

Distanza IV -> FV:

```text
catena Git = 4
globale    = 12
```

Distanza OV -> FV:

```text
catena Git = 1
globale    = 3
```

L'indice globale conta molte release intermedie che non fanno parte della stessa catena Git.

---

## STORM-2231

```text
IV = 1.0.1
OV = 1.0.3
FV = 1.0.5
```

Distanze:

```text
IV -> FV
catena Git = 4
globale    = 8

OV -> FV
catena Git = 2
globale    = 4
```

Anche qui l'indice globale raddoppia entrambe le distanze.

---

## STORM-477

```text
IV = 0.9.2-incubating
OV = 0.9.3
FV = 1.0.0
```

Distanze:

```text
IV -> FV
catena Git = 2
globale    = 6

OV -> FV
catena Git = 1
globale    = 5
```

La differenza è ancora più marcata.

---

# 11. Stato del calcolo attuale

Con l'indice cronologico globale, il check produce:

```text
Ticket usati per P = 183

P min      = 0.2500
P max      = 12.0000
P mediana  = 1.5000
P_total    = 2.2272

P > 1      = 137
P == 0     = 0
P negativi = 0
```

La formula è matematicamente coerente rispetto agli indici usati.

Il problema non è:

```text
come viene calcolato P
```

ma:

```text
che cosa rappresenta la distanza tra release usata nella formula
```

---

# 12. Conclusione metodologica attuale

Al momento sono emersi due limiti opposti.

## Indice cronologico globale

Vantaggi:

- semplice;
- deterministico;
- disponibile per tutte le release;
- utile per ordinare il dataset;
- utile per selezionare il primo 34%.

Limite:

- può contare release di branch parallele;
- in 40 dei 50 casi con lineage Git lineare produce almeno una distanza diversa rispetto alla catena Git.

---

## Ancestry Git

Vantaggi:

- rappresenta la reale relazione tra commit e tag;
- permette di distinguere release appartenenti a catene differenti.

Limite:

- 82/183 ticket non hanno una singola lineage IV/OV/FV;
- una successione semanticamente naturale di release può non risultare lineare nel grafo Git;
- branch, cherry-pick e merge rendono il modello complesso.

---

# 13. Cosa NON va fatto automaticamente

Alla luce dei controlli attuali, non bisogna ancora:

```text
1. sostituire la distanza globale con la distanza Git;
2. escludere tutti i ticket senza single lineage;
3. usare major.minor come vera lineage;
4. cambiare P_total;
5. eliminare i casi IV > OV;
6. inventare un ordinamento di branch;
7. modificare il labeling.
```

Qualunque modifica sarebbe una scelta metodologica aggiuntiva e deve essere giustificata dalla definizione di Proportion usata nel corso o nella fonte originale.

---

# 14. Domanda metodologica aperta

La domanda da risolvere è:

> Quando Proportion parla di distanza in release, intende:
>
> 1. l'ordine cronologico globale di tutte le release del progetto?
> 2. il numero di release lungo una specifica release branch?
> 3. una sequenza di versioni definita diversamente?
> 4. la semplice posizione ordinale fornita dal dataset/version ordering originale?

La consegna della Milestone specifica di usare **Proportion Total**, ma non chiarisce esplicitamente come gestire progetti con linee di release parallele.

---

# 15. Decisione operativa temporanea

Finché la definizione metodologica non viene chiarita:

```text
P_total = 2.2272
```

va considerato:

```text
RISULTATO PROVVISORIO
```

e non definitivo.

La pipeline di Proportion resta tecnicamente funzionante, ma la semantica della distanza tra release è ancora sotto verifica.

---

# 16. Sintesi finale

```text
Indice globale:
    utile per timeline e dataset
    ↓
    non necessariamente adatto a misurare release distance

major.minor:
    utile come diagnostica
    ↓
    non rappresenta una vera lineage

Git ancestry:
    descrive la relazione reale tra commit/tag
    ↓
    ma non tutte le release formano una singola catena

Risultato:
    la formula di P è corretta
    ma la definizione della distanza usata nella formula
    deve ancora essere giustificata metodologicamente
```

## Stato finale del problema

```text
Problema identificato: SI
Errore matematico nella formula: NO
Errore certo nel codice: NON DIMOSTRATO
Rischio di distanza distorta: SI
Evidenza empirica: 40/50 casi lineari con distanza Git != distanza globale
Decisione definitiva: IN ATTESA DI CHIARIMENTO METODOLOGICO
```
"""

path = Path("/mnt/data/PROPORTION_release_distance_problem.md")
path.write_text(md, encoding="utf-8")
print(path)
