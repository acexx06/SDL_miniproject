package com.hms.sim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Deterministic generators for every piece of made-up data in the project: people, doctors,
 * medicines and insurance plans. Nothing here is real. Drug names are assembled from invented
 * stems, and their "uses" are placeholder text, not medical information.
 */
public final class SyntheticData {

    public record Drug(String slug, String name, String category, String form, String strength,
                       String uses, String sideEffects, long pricePaise) {
    }

    public record Plan(String id, String provider, String name, int consultation, int pharmacy, int lab,
                       int emergency, long annualLimitPaise) {
    }

    public record DoctorSeed(String name, String specialization, String qualification, int experienceYears,
                             long feePaise, double rating) {
    }

    public record Zone(String name, int x, int y) {
    }

    public static final List<String> SPECIALIZATIONS = List.of(
            "Cardiology", "Neurology", "Orthopedics", "Pediatrics", "Dermatology", "Gynecology", "Oncology",
            "Psychiatry", "Ophthalmology", "ENT", "Gastroenterology", "Nephrology", "Pulmonology",
            "Endocrinology", "Urology", "General Medicine");

    public static final List<Zone> ZONES = List.of(
            new Zone("Central", 50, 50), new Zone("North Gate", 50, 90), new Zone("South Park", 50, 10),
            new Zone("East Market", 90, 50), new Zone("West End", 10, 50), new Zone("Lakeside", 80, 80),
            new Zone("Old Town", 20, 20), new Zone("Tech Park", 85, 15));

    private static final String[] FIRST_NAMES = {
            "Aarav", "Aditi", "Akash", "Ananya", "Arjun", "Asha", "Bhavna", "Chetan", "Deepa", "Dev", "Divya",
            "Farhan", "Gauri", "Harsh", "Isha", "Jatin", "Kavya", "Kiran", "Lakshmi", "Manoj", "Meera", "Mohan",
            "Naveen", "Neha", "Nikhil", "Pooja", "Pranav", "Priya", "Rahul", "Rajesh", "Rekha", "Ritu", "Rohan",
            "Sakshi", "Sameer", "Sanjay", "Sara", "Shreya", "Sneha", "Suresh", "Tanvi", "Tarun", "Uma", "Varun",
            "Vidya", "Vikram", "Yash", "Zoya"};

    private static final String[] LAST_NAMES = {
            "Agarwal", "Ahuja", "Bajaj", "Banerjee", "Bhatt", "Chawla", "Chopra", "Das", "Desai", "Dutta",
            "Fernandes", "Gandhi", "Ghosh", "Gill", "Goyal", "Gupta", "Iyer", "Jain", "Joshi", "Kapoor", "Khan",
            "Kulkarni", "Kumar", "Malhotra", "Mehta", "Menon", "Mishra", "Nair", "Pandey", "Patel", "Pillai",
            "Rao", "Reddy", "Saxena", "Sen", "Shah", "Sharma", "Shetty", "Singh", "Sinha", "Tiwari", "Varma",
            "Verma", "Yadav", "Bose", "Dsouza", "Thomas", "Mathur"};

    private static final String[] QUALIFICATIONS = {"MBBS, MD", "MBBS, MS", "MBBS, DNB", "MBBS, MD, DM", "MBBS, MS, MCh"};

    private static final String[] DRUG_STEMS = {
            "Zyv", "Quor", "Brix", "Nuv", "Thal", "Vex", "Kyr", "Plim", "Drax", "Fen", "Orb", "Xil", "Jurn", "Wex",
            "Hyl", "Miv", "Sarn", "Cel", "Dov", "Elr", "Gant", "Iv", "Lum", "Tyr", "Rav", "Bex", "Pym", "Nix"};
    private static final String[] DRUG_VOWELS = {"a", "o", "i", "e", "u"};
    /** suffix, category */
    private static final String[][] DRUG_CLASSES = {
            {"cillin", "Antibiotic"}, {"mycin", "Antibiotic"}, {"statin", "Cholesterol"}, {"pril", "Blood Pressure"},
            {"sartan", "Blood Pressure"}, {"olol", "Heart"}, {"prazole", "Antacid"}, {"formin", "Diabetes"},
            {"profen", "Pain Relief"}, {"tadine", "Allergy"}, {"zepam", "Sedative"}, {"vir", "Antiviral"}};
    private static final String[] DRUG_FORMS = {"Tablet", "Capsule", "Syrup", "Injection", "Ointment"};
    private static final String[] DRUG_STRENGTHS = {"5 mg", "10 mg", "25 mg", "50 mg", "100 mg", "250 mg", "500 mg"};
    private static final String[] SIDE_EFFECTS = {"nausea", "drowsiness", "headache", "dry mouth", "dizziness", "mild rash"};

    private static final String[] PROVIDERS = {
            "Northwind Health Cover", "Bluepeak Assurance", "Greenfern Mutual", "Silverbrook Health",
            "Sunridge Care Cover", "Maplestone Assurance"};
    private static final String[] TIERS = {"Basic", "Standard", "Plus", "Premium", "Family"};

    private SyntheticData() {
    }

    public static String slug(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    public static String personName(Random rng) {
        return FIRST_NAMES[rng.nextInt(FIRST_NAMES.length)] + " " + LAST_NAMES[rng.nextInt(LAST_NAMES.length)];
    }

    /** The same {@code n} drugs every time, so the pharmacy stock and the crawled site line up. */
    public static List<Drug> drugs(int n) {
        List<String[]> combos = new ArrayList<>();
        for (String stem : DRUG_STEMS) {
            for (String vowel : DRUG_VOWELS) {
                for (String[] drugClass : DRUG_CLASSES) {
                    combos.add(new String[]{stem + vowel + drugClass[0], drugClass[1]});
                }
            }
        }
        Random rng = new Random(7);
        Collections.shuffle(combos, rng);
        List<Drug> drugs = new ArrayList<>(n);
        for (int i = 0; i < Math.min(n, combos.size()); i++) {
            String name = combos.get(i)[0];
            String category = combos.get(i)[1];
            String uses = "Placeholder text: listed under " + category.toLowerCase(Locale.ROOT)
                    + " in this simulated catalogue.";
            String sideEffects = SIDE_EFFECTS[rng.nextInt(SIDE_EFFECTS.length)] + ", "
                    + SIDE_EFFECTS[rng.nextInt(SIDE_EFFECTS.length)];
            drugs.add(new Drug(slug(name), name, category, DRUG_FORMS[rng.nextInt(DRUG_FORMS.length)],
                    DRUG_STRENGTHS[rng.nextInt(DRUG_STRENGTHS.length)], uses, sideEffects,
                    (20 + rng.nextInt(880)) * 100L));
        }
        return drugs;
    }

    public static List<Plan> plans() {
        List<Plan> plans = new ArrayList<>();
        for (int p = 0; p < PROVIDERS.length; p++) {
            for (int t = 0; t < TIERS.length; t++) {
                int base = 30 + t * 12 + p * 2;
                plans.add(new Plan("PLAN-" + (p + 1) + (t + 1), PROVIDERS[p], TIERS[t],
                        Math.min(95, base + 10), Math.min(90, base), Math.min(90, base + 5), Math.min(100, base + 20),
                        (2 + t * 2L) * 10_000_000L));
            }
        }
        return plans;
    }

    public static List<DoctorSeed> doctors(int n) {
        Random rng = new Random(11);
        Set<String> names = new LinkedHashSet<>();
        while (names.size() < n) {
            names.add("Dr. " + personName(rng));
        }
        List<DoctorSeed> doctors = new ArrayList<>(n);
        int i = 0;
        for (String name : names) {
            doctors.add(new DoctorSeed(name, SPECIALIZATIONS.get(i++ % SPECIALIZATIONS.size()),
                    QUALIFICATIONS[rng.nextInt(QUALIFICATIONS.length)], 2 + rng.nextInt(34),
                    (6 + rng.nextInt(25)) * 5000L, Math.round((3.5 + rng.nextDouble() * 1.5) * 10) / 10.0));
        }
        return doctors;
    }
}
