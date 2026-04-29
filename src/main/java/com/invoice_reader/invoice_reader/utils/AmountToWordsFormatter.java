package com.invoice_reader.invoice_reader.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

public final class AmountToWordsFormatter {

    private AmountToWordsFormatter() {
    }

    public static String formatTtcInWords(Object rawAmount) {
        BigDecimal amount = toBigDecimal(rawAmount);
        if (amount == null) {
            return "";
        }

        BigDecimal scaled = amount.abs().setScale(2, RoundingMode.HALF_UP);
        long dirhams = scaled.longValue();
        int cents = scaled.remainder(BigDecimal.ONE)
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();

        if (amount.signum() < 0 && (dirhams > 0 || cents > 0)) {
            return "moins " + formatPositive(Math.abs(dirhams), cents);
        }
        return formatPositive(dirhams, cents);
    }

    private static String formatPositive(long dirhams, int cents) {
        StringBuilder builder = new StringBuilder();
        builder.append(convertNumber(dirhams)).append(dirhams == 1 ? " dirham" : " dirhams");
        if (cents > 0) {
            builder.append(" et ").append(convertNumber(cents)).append(cents == 1 ? " centime" : " centimes");
        }
        return builder.toString().trim().replaceAll("\\s+", " ");
    }

    private static BigDecimal toBigDecimal(Object rawAmount) {
        if (rawAmount == null) {
            return null;
        }
        if (rawAmount instanceof BigDecimal bd) {
            return bd;
        }
        if (rawAmount instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        String value = String.valueOf(rawAmount).trim();
        if (value.isBlank()) {
            return null;
        }
        String normalized = value
                .replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replaceAll("\\s+", "")
                .replaceAll("[^0-9,\\.\\-]", "")
                .replace(",", ".");
        if (normalized.isBlank() || "-".equals(normalized) || ".".equals(normalized)) {
            return null;
        }
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String convertNumber(long number) {
        if (number == 0) {
            return "zero";
        }
        if (number < 0) {
            return "moins " + convertNumber(Math.abs(number));
        }

        StringBuilder result = new StringBuilder();

        long billions = number / 1_000_000_000L;
        if (billions > 0) {
            result.append(convertNumber(billions)).append(billions == 1 ? " milliard" : " milliards");
            number %= 1_000_000_000L;
            if (number > 0) {
                result.append(" ");
            }
        }

        long millions = number / 1_000_000L;
        if (millions > 0) {
            result.append(convertNumber(millions)).append(millions == 1 ? " million" : " millions");
            number %= 1_000_000L;
            if (number > 0) {
                result.append(" ");
            }
        }

        long thousands = number / 1000L;
        if (thousands > 0) {
            if (thousands == 1) {
                result.append("mille");
            } else {
                result.append(convertUnderThousand((int) thousands)).append(" mille");
            }
            number %= 1000L;
            if (number > 0) {
                result.append(" ");
            }
        }

        if (number > 0) {
            result.append(convertUnderThousand((int) number));
        }

        return result.toString().trim().replaceAll("\\s+", " ");
    }

    private static String convertUnderThousand(int number) {
        if (number == 0) {
            return "";
        }
        if (number < 100) {
            return convertUnderHundred(number);
        }

        int hundreds = number / 100;
        int rest = number % 100;

        StringBuilder result = new StringBuilder();
        if (hundreds == 1) {
            result.append("cent");
        } else {
            result.append(convertUnderHundred(hundreds)).append(" cent");
        }
        if (rest == 0 && hundreds > 1) {
            result.append("s");
        } else if (rest > 0) {
            result.append(" ").append(convertUnderHundred(rest));
        }
        return result.toString();
    }

    private static String convertUnderHundred(int number) {
        String[] units = {
                "zero", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf",
                "dix", "onze", "douze", "treize", "quatorze", "quinze", "seize"
        };
        if (number < units.length) {
            return units[number];
        }
        if (number < 20) {
            return "dix-" + units[number - 10];
        }
        if (number < 70) {
            int tens = number / 10;
            int unit = number % 10;
            String[] tensWords = {"", "", "vingt", "trente", "quarante", "cinquante", "soixante"};
            String base = tensWords[tens];
            if (unit == 0) {
                return base;
            }
            if (unit == 1) {
                return base + " et un";
            }
            return base + "-" + units[unit];
        }
        if (number < 80) {
            if (number == 71) {
                return "soixante et onze";
            }
            return "soixante-" + convertUnderHundred(number - 60);
        }
        if (number < 100) {
            if (number == 80) {
                return "quatre-vingts";
            }
            if (number == 81) {
                return "quatre-vingt-un";
            }
            return "quatre-vingt-" + convertUnderHundred(number - 80);
        }
        return String.valueOf(number).toLowerCase(Locale.ROOT);
    }
}
