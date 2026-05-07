CREATE TABLE IF NOT EXISTS Cptjournal (
    Numero      BIGINT         NOT NULL,
    ndosjrn     VARCHAR(50),
    nmois       INT,
    Mois        VARCHAR(30),
    ncompt      VARCHAR(20),
    ecriture    VARCHAR(500),
    debit       DECIMAL(15,2),
    credit      DECIMAL(15,2),
    valider     VARCHAR(5),
    datcompl    DATE,
    dat         INT,
    annee       INT,
    mnt_rester  DECIMAL(15,2),
    PRIMARY KEY (Numero)
);
