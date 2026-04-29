package com.invoice_reader.invoice_reader.servises.account_tier;

import com.invoice_reader.invoice_reader.dto.account_tier.AccountDto;
import com.invoice_reader.invoice_reader.dto.account_tier.CreateAccountRequest;
import com.invoice_reader.invoice_reader.dto.account_tier.UpdateAccountRequest;
import com.invoice_reader.invoice_reader.entity.account_tier.Account;
import com.invoice_reader.invoice_reader.repository.AccountDao;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class AccountService {

    private final AccountDao accountDao;

    // ===================== CRUD =====================

    /**
     * Crée un nouveau compte
     * @param request Données du compte à créer
     * @return DTO du compte créé
     * @throws IllegalArgumentException si le code existe déjà
     */
    @Transactional
    public AccountDto createAccount(CreateAccountRequest request) {
        log.info("Creation compte: code={}, libelle={}", request.getCode(), request.getLibelle());
        request.validate();

        // Validation unicite du code
        if (accountDao.existsByCode(request.getCode())) {
            throw new IllegalArgumentException("Un compte avec le code " + request.getCode() + " existe deja");
        }

        // Creation de l'entite
        Account account = Account.builder()
                .code(request.getCode())
                .libelle(request.getLibelle())
                .classe(request.getClasse())
                .tvaRate(request.getTvaRate())
                .active(request.getActive() != null ? request.getActive() : true)
                .xCom(request.getXCom())
                .delai(request.getDelai())
                .ville(request.getVille())
                .adresse(request.getAdresse())
                .activite(request.getActivite())
                .cdClt(request.getCdClt())
                .cdFrs(request.getCdFrs())
                .typeCmpt(request.getTypeCmpt())
                .numcat(request.getNumcat())
                .idF(request.getIdF())
                .cod(request.getCod())
                .cnss(request.getCnss())
                .tp(request.getTp())
                .ice(request.getIce())
                .rc(request.getRc())
                .rib(request.getRib())
                .tva(request.getTva())
                .charge(request.getCharge())
                .createdBy(request.getCreatedBy())
                .updatedBy(request.getUpdatedBy())
                .build();

        Account saved = accountDao.save(account);

        log.info("Compte cree: ID={}, code={}, classe={}",
                saved.getId(), saved.getCode(), saved.getClasse());

        return AccountDto.fromEntity(saved);
    }

    /**
     * Met à jour un compte existant
     * @param id ID du compte
     * @param request Nouvelles données
     * @return DTO du compte mis à jour
     * @throws IllegalArgumentException si le compte n'existe pas
     */
    @Transactional
    public AccountDto updateAccount(Long id, UpdateAccountRequest request) {
        log.info("Mise à jour compte ID={}", id);

        Account account = accountDao.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Compte non trouvé: " + id));

        // Mise à jour des champs modifiables
        if (request.getLibelle() != null && !request.getLibelle().isBlank()) {
            account.setLibelle(request.getLibelle());
            log.debug("  Libellé mis à jour: {}", request.getLibelle());
        }

        if (request.getActive() != null) {
            account.setActive(request.getActive());
            log.debug("  Statut actif mis à jour: {}", request.getActive());
        }

        if (request.getTvaRate() != null) {
            account.setTvaRate(request.getTvaRate());
            log.debug("  Taux TVA mis à jour: {}", request.getTvaRate());
        }

        if (request.getXCom() != null) account.setXCom(request.getXCom());
        if (request.getDelai() != null) account.setDelai(request.getDelai());
        if (request.getVille() != null) account.setVille(request.getVille());
        if (request.getAdresse() != null) account.setAdresse(request.getAdresse());
        if (request.getActivite() != null) account.setActivite(request.getActivite());
        if (request.getCdClt() != null) account.setCdClt(request.getCdClt());
        if (request.getCdFrs() != null) account.setCdFrs(request.getCdFrs());
        if (request.getTypeCmpt() != null) account.setTypeCmpt(request.getTypeCmpt());
        if (request.getNumcat() != null) account.setNumcat(request.getNumcat());
        if (request.getIdF() != null) account.setIdF(request.getIdF());
        if (request.getCod() != null) account.setCod(request.getCod());
        if (request.getCnss() != null) account.setCnss(request.getCnss());
        if (request.getTp() != null) account.setTp(request.getTp());
        if (request.getIce() != null) account.setIce(request.getIce());
        if (request.getRc() != null) account.setRc(request.getRc());
        if (request.getRib() != null) account.setRib(request.getRib());
        if (request.getTva() != null) account.setTva(request.getTva());
        if (request.getCharge() != null) account.setCharge(request.getCharge());

        if (request.getUpdatedBy() != null) {
            account.setUpdatedBy(request.getUpdatedBy());
        }

        Account saved = accountDao.save(account);
        log.info("Compte mis à jour: ID={}, code={}", saved.getId(), saved.getCode());

        return AccountDto.fromEntity(saved);
    }

    /**
     * Récupère un compte par son ID
     * @param id ID du compte
     * @return DTO du compte
     */
    @Transactional(readOnly = true)
    public Optional<AccountDto> getAccountById(Long id) {
        return accountDao.findById(id)
                .map(AccountDto::fromEntity);
    }

    /**
     * Récupère un compte par son code
     * @param code Code du compte
     * @return DTO du compte
     */
    @Transactional(readOnly = true)
    public Optional<AccountDto> getAccountByCode(String code) {
        return accountDao.findByCode(code)
                .map(AccountDto::fromEntity);
    }

    /**
     * Récupère tous les comptes actifs, triés par code
     * @return Liste des comptes actifs
     */
    @Transactional(readOnly = true)
    public List<AccountDto> getAllActiveAccounts() {
        return accountDao.findByActiveTrueOrderByCodeAsc().stream()
                .map(AccountDto::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Récupère tous les comptes (actifs et inactifs), triés par code
     * @return Liste de tous les comptes
     */
    @Transactional(readOnly = true)
    public List<AccountDto> getAllAccounts() {
        return accountDao.findAllByOrderByCodeAsc().stream()
                .map(AccountDto::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<Map<String, String>> getAccountOptions() {
        return accountDao.findByActiveTrueOrderByCodeAsc().stream()
                .map(account -> Map.of(
                        "code", account.getCode(),
                        "libelle", account.getLibelle()
                ))
                .collect(Collectors.toList());
    }

    /**
     * Désactive un compte (soft delete)
     * @param id ID du compte
     * @throws IllegalArgumentException si le compte n'existe pas
     */
    @Transactional
    public void deactivateAccount(Long id) {
        log.info("Désactivation compte ID={}", id);

        Account account = accountDao.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Compte non trouvé: " + id));

        account.deactivate();
        accountDao.save(account);

        log.info("Compte désactivé: ID={}, code={}", account.getId(), account.getCode());
    }

    /**
     * Réactive un compte
     * @param id ID du compte
     * @throws IllegalArgumentException si le compte n'existe pas
     */
    @Transactional
    public void activateAccount(Long id) {
        log.info("Activation compte ID={}", id);

        Account account = accountDao.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Compte non trouvé: " + id));

        account.activate();
        accountDao.save(account);

        log.info("Compte activé: ID={}, code={}", account.getId(), account.getCode());
    }

    // ===================== RECHERCHE PAR CLASSE =====================

    /**
     * Récupère tous les comptes d'une classe donnée
     * @param classe Numéro de classe (1-8)
     * @return Liste des comptes de cette classe
     */
    @Transactional(readOnly = true)
    public List<AccountDto> getAccountsByClasse(Integer classe) {
        log.debug("Recherche comptes classe {}", classe);

        if (classe == null || classe < 1 || classe > 8) {
            throw new IllegalArgumentException("La classe doit être entre 1 et 8");
        }

        return accountDao.findByClasseAndActiveTrue(classe).stream()
                .map(AccountDto::fromEntity)
                .collect(Collectors.toList());
    }

    // ===================== COMPTES SPÉCIFIQUES =====================

    /**
     * Récupère tous les comptes fournisseurs (441XXX)
     * @return Liste des comptes fournisseurs actifs
     */
    @Transactional(readOnly = true)
    public List<AccountDto> getFournisseurAccounts() {
        log.debug("Recherche comptes fournisseurs (441XXX)");

        return accountDao.findFournisseurAccounts().stream()
                .map(AccountDto::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Récupère tous les comptes de charge (classe 6)
     * @return Liste des comptes de charge actifs
     */
    @Transactional(readOnly = true)
    public List<AccountDto> getChargeAccounts() {
        log.debug("Recherche comptes de charge (classe 6)");

        return accountDao.findChargeAccounts().stream()
                .map(AccountDto::fromEntity)
                .collect(Collectors.toList());
    }

    /**
     * Récupère tous les comptes TVA (345/445)
     * @return Liste des comptes TVA actifs
     */
    @Transactional(readOnly = true)
    public List<AccountDto> getTvaAccounts() {
        log.debug("Recherche comptes TVA (345/445)");

        return accountDao.findTvaAccounts().stream()
                .map(AccountDto::fromEntity)
                .collect(Collectors.toList());
    }

    // ===================== RECHERCHE =====================

    /**
     * Recherche des comptes par code ou libellé
     * @param query Texte de recherche
     * @return Liste des comptes correspondants (actifs uniquement)
     */
    @Transactional(readOnly = true)
    public List<AccountDto> searchAccounts(String query) {
        log.debug("Recherche comptes: query={}", query);

        if (query == null || query.isBlank()) {
            return getAllActiveAccounts();
        }

        return accountDao.searchActive(query).stream()
                .map(AccountDto::fromEntity)
                .collect(Collectors.toList());
    }

    // ===================== STATISTIQUES =====================

    /**
     * Récupère les statistiques du plan comptable
     * @return Map contenant les statistiques
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getStatistics() {
        log.debug("Récupération statistiques plan comptable");

        long totalAccounts = accountDao.count();
        long activeAccounts = accountDao.countByActiveTrue();

        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("totalAccounts", totalAccounts);
        stats.put("activeAccounts", activeAccounts);
        stats.put("inactiveAccounts", totalAccounts - activeAccounts);

        // Statistiques par classe
        Map<Integer, Long> byClasse = new java.util.LinkedHashMap<>();
        for (int i = 1; i <= 8; i++) {
            long count = accountDao.countByClasse(i);
            if (count > 0) {
                byClasse.put(i, count);
            }
        }
        stats.put("byClasse", byClasse);

        // Comptes spéciaux
        stats.put("fournisseurAccounts", accountDao.findFournisseurAccounts().size());
        stats.put("chargeAccounts", accountDao.findChargeAccounts().size());
        stats.put("tvaAccounts", accountDao.findTvaAccounts().size());

        return stats;
    }

    // ===================== IMPORT EN MASSE =====================

    /**
     * Importe une liste de comptes en masse
     * @param requests Liste de comptes à créer
     * @return Liste des comptes créés
     */
    @Transactional
    public List<AccountDto> importAccounts(List<CreateAccountRequest> requests) {
        log.info("Import en masse de {} comptes", requests.size());

        List<AccountDto> created = new java.util.ArrayList<>();
        int successCount = 0;
        int errorCount = 0;

        for (CreateAccountRequest request : requests) {
            try {
                // Vérifier si le compte existe déjà
                if (accountDao.existsByCode(request.getCode())) {
                    log.warn("Compte {} déjà existant, ignoré", request.getCode());
                    errorCount++;
                    continue;
                }

                AccountDto account = createAccount(request);
                created.add(account);
                successCount++;

            } catch (Exception e) {
                log.error("Erreur import compte {}: {}", request.getCode(), e.getMessage());
                errorCount++;
            }
        }

        log.info("Import terminé: {} succès, {} erreurs", successCount, errorCount);

        return created;
    }

    // ===================== VALIDATION =====================

    /**
     * Valide qu'un code de compte existe
     * @param code Code du compte
     * @return true si le compte existe
     */
    @Transactional(readOnly = true)
    public boolean accountExists(String code) {
        return accountDao.existsByCode(code);
    }

    /**
     * Valide qu'un code de compte existe et est actif
     * @param code Code du compte
     * @return true si le compte existe et est actif
     */
    @Transactional(readOnly = true)
    public boolean accountExistsAndActive(String code) {
        return accountDao.findByCode(code)
                .map(Account::getActive)
                .orElse(false);
    }

    /**
     * Valide le format d'un code de compte
     * @param code Code à valider
     * @return true si le format est valide (4-10 chiffres)
     */
    public boolean isValidAccountCode(String code) {
        return code != null && code.matches("^\\d{4,10}$");
    }

    /**
     * Valide qu'un compte fournisseur est valide (format 441XXX)
     * @param code Code du compte
     * @return true si le format est valide
     */
    public boolean isValidFournisseurAccount(String code) {
        return code != null && code.matches("^441\\d{3,7}$");
    }
}
