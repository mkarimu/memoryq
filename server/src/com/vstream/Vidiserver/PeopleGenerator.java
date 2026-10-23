package com.vstream.Vidiserver;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The "AI" of the profiler: invents a believable test person from built-in, country-specific lists (names,
 * cities, universities, flag colours) plus shared lists (studies, hobbies, stage-name styles). Works offline.
 * Every invented person is a test account and is labelled as one.
 */
public final class PeopleGenerator {

    /** One invented person, before it becomes an account. */
    public static final class Person {
        public final String firstName;
        public final String lastName;
        public final String stageName;
        public final String city;
        public final String country;
        public final String education;
        public final List<String> hobbies;
        public final long seed;

        Person(String firstName, String lastName, String stageName, String city, String country, String education,
               List<String> hobbies, long seed) {
            this.firstName = firstName;
            this.lastName = lastName;
            this.stageName = stageName;
            this.city = city;
            this.country = country;
            this.education = education;
            this.hobbies = hobbies;
            this.seed = seed;
        }
    }

    static final class Country {
        final String name;
        final String[] men;
        final String[] women;
        final String[] last;
        final String[] cities;
        final String[] schools;
        final int[] colors;

        Country(String name, String[] men, String[] women, String[] last, String[] cities, String[] schools, int[] colors) {
            this.name = name;
            this.men = men;
            this.women = women;
            this.last = last;
            this.cities = cities;
            this.schools = schools;
            this.colors = colors;
        }
    }

    private static String[] a(String... s) {
        return s;
    }

    private static int[] c(int... v) {
        return v;
    }

    static final Country[] COUNTRIES = {
        new Country("Nigeria", a("Chinedu", "Emeka", "Tunde", "Ibrahim", "Femi", "Segun"),
                a("Ngozi", "Amaka", "Funmilayo", "Zainab", "Chiamaka", "Tolu"),
                a("Okafor", "Adeyemi", "Bello", "Eze", "Nwosu", "Balogun"),
                a("Lagos", "Abuja", "Enugu", "Ibadan", "Port Harcourt"),
                a("University of Lagos", "Obafemi Awolowo University", "University of Nigeria, Nsukka", "Covenant University"),
                c(0x008751, 0xFFFFFF, 0x008751)),
        new Country("Kenya", a("Kamau", "Otieno", "Kiprop", "Mwangi", "Baraka", "Omondi"),
                a("Wanjiru", "Achieng", "Njeri", "Akinyi", "Chebet", "Wambui"),
                a("Mwangi", "Odhiambo", "Kariuki", "Wekesa", "Kiplagat", "Njoroge"),
                a("Nairobi", "Mombasa", "Kisumu", "Nakuru", "Eldoret"),
                a("University of Nairobi", "Kenyatta University", "Strathmore University", "Moi University"),
                c(0x000000, 0xBB0000, 0x006600)),
        new Country("Brazil", a("Lucas", "Gabriel", "Rafael", "Thiago", "Matheus", "Pedro"),
                a("Ana", "Beatriz", "Camila", "Larissa", "Julia", "Mariana"),
                a("Silva", "Santos", "Oliveira", "Souza", "Costa", "Pereira"),
                a("Sao Paulo", "Rio de Janeiro", "Salvador", "Belo Horizonte", "Recife"),
                a("University of Sao Paulo", "Federal University of Rio de Janeiro", "Unicamp", "PUC-Rio"),
                c(0x009C3B, 0xFFDF00, 0x002776)),
        new Country("India", a("Arjun", "Rohan", "Vikram", "Aditya", "Karan", "Rahul"),
                a("Priya", "Ananya", "Kavya", "Meera", "Isha", "Diya"),
                a("Sharma", "Patel", "Iyer", "Reddy", "Singh", "Nair"),
                a("Mumbai", "Bengaluru", "Delhi", "Chennai", "Pune"),
                a("IIT Bombay", "University of Delhi", "Anna University", "Christ University"),
                c(0xFF9933, 0xFFFFFF, 0x138808)),
        new Country("Japan", a("Haruto", "Ren", "Sota", "Yuto", "Kaito", "Riku"),
                a("Yui", "Sakura", "Hina", "Aoi", "Mio", "Rin"),
                a("Sato", "Suzuki", "Takahashi", "Tanaka", "Watanabe", "Ito"),
                a("Tokyo", "Osaka", "Kyoto", "Sapporo", "Fukuoka"),
                a("University of Tokyo", "Kyoto University", "Waseda University", "Keio University"),
                c(0xFFFFFF, 0xBC002D, 0xFFFFFF)),
        new Country("Germany", a("Lukas", "Felix", "Jonas", "Leon", "Paul", "Finn"),
                a("Hannah", "Lea", "Mia", "Lena", "Emma", "Clara"),
                a("Mueller", "Schmidt", "Schneider", "Fischer", "Weber", "Wagner"),
                a("Berlin", "Hamburg", "Munich", "Cologne", "Leipzig"),
                a("LMU Munich", "Humboldt University of Berlin", "TU Berlin", "Heidelberg University"),
                c(0x000000, 0xDD0000, 0xFFCE00)),
        new Country("Mexico", a("Diego", "Santiago", "Mateo", "Emiliano", "Leonardo", "Sebastian"),
                a("Sofia", "Valentina", "Ximena", "Regina", "Camila", "Renata"),
                a("Hernandez", "Garcia", "Martinez", "Lopez", "Ramirez", "Flores"),
                a("Mexico City", "Guadalajara", "Monterrey", "Puebla", "Oaxaca"),
                a("UNAM", "Tec de Monterrey", "University of Guadalajara", "IPN"),
                c(0x006847, 0xFFFFFF, 0xCE1126)),
        new Country("Philippines", a("Joshua", "Miguel", "Paolo", "Carlo", "Rafael", "Gabriel"),
                a("Angel", "Bea", "Nicole", "Patricia", "Andrea", "Kristine"),
                a("Santos", "Reyes", "Cruz", "Bautista", "Garcia", "Mendoza"),
                a("Manila", "Cebu City", "Davao City", "Quezon City", "Iloilo City"),
                a("University of the Philippines", "Ateneo de Manila University", "De La Salle University", "University of Santo Tomas"),
                c(0x0038A8, 0xFFFFFF, 0xCE1126)),
        new Country("Egypt", a("Omar", "Youssef", "Karim", "Mostafa", "Ahmed", "Hassan"),
                a("Nour", "Mariam", "Salma", "Farida", "Laila", "Habiba"),
                a("Hassan", "Mahmoud", "Ibrahim", "Ali", "Farouk", "Saleh"),
                a("Cairo", "Alexandria", "Giza", "Luxor", "Aswan"),
                a("Cairo University", "American University in Cairo", "Ain Shams University", "Alexandria University"),
                c(0xCE1126, 0xFFFFFF, 0x000000)),
        new Country("Canada", a("Liam", "Noah", "Ethan", "Owen", "Logan", "Nathan"),
                a("Olivia", "Emma", "Chloe", "Ava", "Charlotte", "Maya"),
                a("Tremblay", "Martin", "Roy", "Wilson", "MacDonald", "Gagnon"),
                a("Toronto", "Vancouver", "Montreal", "Calgary", "Halifax"),
                a("University of Toronto", "McGill University", "University of British Columbia", "University of Waterloo"),
                c(0xFF0000, 0xFFFFFF, 0xFF0000)),
        new Country("United Kingdom", a("Oliver", "Harry", "George", "Jack", "Charlie", "Alfie"),
                a("Amelia", "Isla", "Grace", "Freya", "Poppy", "Evie"),
                a("Smith", "Jones", "Taylor", "Brown", "Evans", "Walker"),
                a("London", "Manchester", "Edinburgh", "Bristol", "Cardiff"),
                a("University of Manchester", "University of Edinburgh", "King's College London", "University of Bristol"),
                c(0x012169, 0xFFFFFF, 0xC8102E)),
        new Country("South Korea", a("Minjun", "Jihoon", "Seojun", "Hyunwoo", "Dohyun", "Jiho"),
                a("Jiwoo", "Seoyeon", "Minji", "Haeun", "Yuna", "Soyeon"),
                a("Kim", "Lee", "Park", "Choi", "Jung", "Kang"),
                a("Seoul", "Busan", "Incheon", "Daegu", "Gwangju"),
                a("Seoul National University", "Yonsei University", "KAIST", "Korea University"),
                c(0xFFFFFF, 0xCD2E3A, 0x0047A0)),
        new Country("Ghana", a("Kwame", "Kofi", "Yaw", "Kojo", "Kwabena", "Kwesi"),
                a("Ama", "Akosua", "Abena", "Efua", "Adwoa", "Esi"),
                a("Mensah", "Owusu", "Boateng", "Asante", "Appiah", "Osei"),
                a("Accra", "Kumasi", "Tamale", "Cape Coast", "Takoradi"),
                a("University of Ghana", "KNUST", "University of Cape Coast", "Ashesi University"),
                c(0xCE1126, 0xFCD116, 0x006B3F)),
    };

    private static final String[] STUDIES = {"BSc in Computer Science", "BA in Music", "BA in Business",
        "BSc in Nursing", "Diploma in Graphic Design", "BA in Journalism", "BEng in Civil Engineering",
        "BSc in Economics", "BA in Film Studies", "Diploma in Marketing", "BArch in Architecture", "BSc in Biology",
        "Diploma in Fashion Design", "BSc in Sports Science", "BA in Psychology", "Diploma in Photography",
        "MSc in Data Science", "MA in Media Studies", "BEng in Electrical Engineering", "BA in Theatre Arts"};
    private static final String[] HOBBIES = {"photography", "football", "cooking", "gaming", "dancing", "hiking",
        "painting", "playing guitar", "reading", "chess", "cycling", "yoga", "singing", "travel vlogging",
        "basketball", "gardening", "poetry", "skateboarding", "fashion", "baking", "swimming", "DJing",
        "podcasting", "watching films", "anime", "volunteering", "running", "drawing"};
    private static final String[] ADJ = {"Golden", "Electric", "Midnight", "Cosmic", "Velvet", "Wild", "Lucky",
        "Neon", "Silver", "Sunny"};
    private static final String[] NOUN = {"Nova", "Echo", "Vibe", "Pulse", "Wave", "Blaze", "Star", "Rhythm",
        "Flame", "Groove"};

    private final Random random;

    public PeopleGenerator(Random random) {
        this.random = random;
    }

    private String pick(String[] list) {
        return list[random.nextInt(list.length)];
    }

    /** Invents one person from a country not in avoid (if possible). */
    public Person invent(Collection<String> avoidCountries) {
        List<Country> choices = new ArrayList<Country>();
        for (Country c : COUNTRIES) {
            if (avoidCountries == null || !avoidCountries.contains(c.name)) {
                choices.add(c);
            }
        }
        if (choices.isEmpty()) {
            for (Country c : COUNTRIES) {
                choices.add(c);
            }
        }
        Country c = choices.get(random.nextInt(choices.size()));
        boolean woman = random.nextBoolean();
        String first = pick(woman ? c.women : c.men);
        String last = pick(c.last);
        String stage;
        switch (random.nextInt(6)) {
            case 0: stage = "DJ " + first; break;
            case 1: stage = first + " " + pick(NOUN); break;
            case 2: stage = "MC " + first; break;
            case 3: stage = first + " the " + pick(ADJ); break;
            case 4: stage = (woman ? "Lady " : "King ") + first; break;
            default: stage = pick(ADJ) + " " + pick(NOUN); break;
        }
        String education = pick(STUDIES) + ", " + pick(c.schools);
        List<String> hobbies = new ArrayList<String>();
        int n = 3 + random.nextInt(2);
        while (hobbies.size() < n) {
            String h = pick(HOBBIES);
            if (!hobbies.contains(h)) {
                hobbies.add(h);
            }
        }
        return new Person(first, last, stage, pick(c.cities), c.name, education, hobbies, random.nextLong());
    }

    /** A username such as "amaka_o27": letters, numbers and _ only, 3 to 24 characters. */
    public String username(Person p) {
        String base = (p.firstName + "_" + p.lastName.charAt(0)).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        if (base.length() > 20) {
            base = base.substring(0, 20);
        }
        return base + (10 + random.nextInt(90));
    }

    /** Flag colours for a country name, or null. */
    static int[] colors(String country) {
        for (Country c : COUNTRIES) {
            if (c.name.equalsIgnoreCase(country == null ? "" : country.trim())) {
                return c.colors;
            }
        }
        return null;
    }

    static int countryCount() {
        return COUNTRIES.length;
    }
}