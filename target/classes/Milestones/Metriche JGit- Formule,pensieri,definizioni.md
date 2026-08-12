# Documentazione Metodologica - Milestone 1 (STORM)

In questa sezione sono riportati i criteri di calcolo adottati per le feature del dataset relativo al progetto **Apache STORM**. L'obiettivo è garantire la consistenza dei dati per la successiva fase di addestramento del classificatore.

## 1. Elenco Feature (Commit Metrics)
Per descrivere la storia evolutiva delle classi, sono state selezionate le seguenti metriche:
*   **commitCount**: Numero totale di revisioni per la classe nella release.
*   **fixCommitCount**: Conteggio dei commit di tipo "fix" (mappati tramite ticket Jira).
*   **churn**: Somma delle righe aggiunte e rimosse.
*   **averageChangeSetSize**: Dimensione media dei file committati insieme alla classe.
*   **distinctAuthors**: Numero di sviluppatori unici che hanno modificato la classe.
*   **daysSinceLastChange**: Giorni trascorsi dall'ultima modifica alla data di release.
*   **changeFrequency**: Frequenza relativa di modifica (vedi dettagli sotto).
*   **changeCountLast90Days**: Numero di modifiche negli ultimi 3 mesi.
*   **modificationIntervalsStdDev**: Variabilità temporale degli interventi.
*   **authorChangeEntropy**: Distribuzione della ownership tra gli autori.

## 2. Note sul Calcolo Tecnico

### changeFrequency
La metrica viene normalizzata rispetto all'anzianità della classe per evitare bias tra classi storiche e nuove:
`changeFrequency = commitCount / classAgeInDays`
*   **classAgeInDays**: `Data Release - Data Primo Commit`.
*   *Nota:* Per classi con un solo commit, l'età viene impostata convenzionalmente a 1 per evitare errori di divisione.

### modificationIntervalsStdDev
Analizza la regolarità del processo di sviluppo calcolando la deviazione standard degli intervalli (in giorni) tra commit consecutivi:
1.  Si calcolano le differenze: \\(\Delta t_1 = date_2 - date_1\\), \\(\Delta t_2 = date_3 - date_2\\), etc.
2.  Si applica la deviazione standard sulla serie di \\(\Delta t\\).
*   *Gestione casi limite:* In presenza di meno di 3 commit (meno di 2 intervalli), il valore è impostato a **0** per mantenere l'integrità del dataset CSV.

### authorChangeEntropy
Utilizza l'entropia di Shannon per misurare quanto il lavoro è frammentato:
\\[H = -\sum_{i=1}^{n} (p_i \times \log_2(p_i))\\]
Dove \\(p_i\\) è la proporzione di commit dell'autore \\(i\\) sul totale. Un'entropia vicina allo **0** indica un forte "owner" principale, mentre valori alti indicano un'attività distribuita (potenzialmente più rischiosa).

