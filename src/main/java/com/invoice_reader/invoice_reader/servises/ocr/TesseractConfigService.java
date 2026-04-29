package com.invoice_reader.invoice_reader.servises.ocr;

import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class TesseractConfigService {

    @Value("${tesseract.path:/usr/bin/tesseract}")
    private String tesseractPath;

    @Value("${tesseract.datapath:/usr/share/tesseract-ocr/4.00/tessdata}")
    private String tessDataPath;

    @Value("${tesseract.language:fra+eng}")
    private String language;
    
    @Value("${tesseract.fast-image-language:fra}")
    private String fastImageLanguage;

    @Value("${tesseract.a4-language:fra+eng}")
    private String a4Language;

    public Tesseract createConfiguredInstance(int attemptNumber) {
        Tesseract tesseract = new Tesseract();

        tesseract.setDatapath(tessDataPath);
        tesseract.setLanguage(language);

        if (attemptNumber <= 0) {
            configureDefault(tesseract);
            log.info("Config Tesseract default: lang={}, PSM=6, OEM=1, DPI=300", language);
        } else {
            configureFallback(tesseract);
            log.info("Config Tesseract fallback: lang={}, PSM=3, OEM=1, DPI=300", language);
        }

        return tesseract;
    }

    public Tesseract createA4FocusedInstance(int attemptNumber) {
        Tesseract tesseract = new Tesseract();

        tesseract.setDatapath(tessDataPath);
        tesseract.setLanguage(a4Language);

        if (attemptNumber <= 0) {
            tesseract.setPageSegMode(6);
            log.info("Config Tesseract A4: lang={}, PSM=6, OEM=1, DPI=320", a4Language);
        } else {
            tesseract.setPageSegMode(3);
            log.info("Config Tesseract A4 fallback: lang={}, PSM=3, OEM=1, DPI=320", a4Language);
        }

        tesseract.setOcrEngineMode(1);
        tesseract.setTessVariable("preserve_interword_spaces", "1");
        tesseract.setTessVariable("user_defined_dpi", "320");
        tesseract.setTessVariable("textord_heavy_nr", "1");
        return tesseract;
    }

    public Tesseract createFastImageInstance(int attemptNumber) {
        Tesseract tesseract = new Tesseract();

        tesseract.setDatapath(tessDataPath);
        tesseract.setLanguage(fastImageLanguage);

        if (attemptNumber <= 0) {
            configureFastImageDefault(tesseract);
            log.info("Config Tesseract image-fast: lang={}, PSM=11, OEM=1, DPI=220", fastImageLanguage);
        } else {
            configureFastImageFallback(tesseract);
            log.info("Config Tesseract image-fast fallback: lang={}, PSM=6, OEM=1, DPI=220", fastImageLanguage);
        }

        return tesseract;
    }

    public Tesseract createA4BadScanInstance(int attemptNumber) {
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(tessDataPath);
        tesseract.setLanguage(a4Language);
        tesseract.setOcrEngineMode(1);
        tesseract.setPageSegMode(attemptNumber <= 0 ? 4 : 6);
        tesseract.setTessVariable("preserve_interword_spaces", "1");
        tesseract.setTessVariable("user_defined_dpi", "300");
        tesseract.setTessVariable("textord_heavy_nr", "1");
        log.info("Config Tesseract A4 bad-scan: lang={}, PSM={}, OEM=1, DPI=300",
                a4Language, attemptNumber <= 0 ? 4 : 6);
        return tesseract;
    }

    private void configureDefault(Tesseract tesseract) {
        tesseract.setPageSegMode(6);
        tesseract.setOcrEngineMode(1);
        tesseract.setTessVariable("preserve_interword_spaces", "1");
        tesseract.setTessVariable("user_defined_dpi", "300");
        tesseract.setTessVariable("textord_heavy_nr", "1");
    }

    private void configureFallback(Tesseract tesseract) {
        tesseract.setPageSegMode(3);
        tesseract.setOcrEngineMode(1);
        tesseract.setTessVariable("preserve_interword_spaces", "1");
        tesseract.setTessVariable("user_defined_dpi", "300");
        tesseract.setTessVariable("textord_heavy_nr", "1");
    }

    private void configureFastImageDefault(Tesseract tesseract) {
        tesseract.setPageSegMode(11);
        tesseract.setOcrEngineMode(1);
        tesseract.setTessVariable("preserve_interword_spaces", "0");
        tesseract.setTessVariable("user_defined_dpi", "220");
        tesseract.setTessVariable("textord_heavy_nr", "0");
        tesseract.setTessVariable("load_system_dawg", "0");
        tesseract.setTessVariable("load_freq_dawg", "0");
    }

    private void configureFastImageFallback(Tesseract tesseract) {
        tesseract.setPageSegMode(6);
        tesseract.setOcrEngineMode(1);
        tesseract.setTessVariable("preserve_interword_spaces", "0");
        tesseract.setTessVariable("user_defined_dpi", "220");
        tesseract.setTessVariable("textord_heavy_nr", "0");
        tesseract.setTessVariable("load_system_dawg", "0");
        tesseract.setTessVariable("load_freq_dawg", "0");
    }
}
