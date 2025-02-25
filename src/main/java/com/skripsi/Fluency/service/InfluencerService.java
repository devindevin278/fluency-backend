package com.skripsi.Fluency.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skripsi.Fluency.model.dto.*;
import com.skripsi.Fluency.model.entity.*;
import com.skripsi.Fluency.repository.*;
import jakarta.persistence.criteria.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Range;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import java.time.LocalDate;

import static org.springframework.util.StringUtils.capitalize;
import org.springframework.web.client.HttpClientErrorException;



@Service
public class InfluencerService {

    public ResponseEntity<?> getInfluencer(String id) {

//        User user = userRepository.findById(Integer.valueOf(userId)).orElse(null);

        Influencer influencer = this.influencerRepository.findById(Integer.valueOf(id)).orElse(null);

        if(influencer == null) {
            return ResponseEntity.notFound().build();
        }

        InfluencerFilterResponseDto influencerFilterResponseDto = buildResponse(influencer, null);
        return ResponseEntity.ok(influencerFilterResponseDto);
    }

//    ini parse range untuk age
    private Range<Integer> parseRange(String value) {
        value = value.toLowerCase(); // Ubah ke lowercase untuk konsistensi
        if (value.contains("-")) { // Format seperti "1k - 10k" atau "1M - 10M"
            String[] parts = value.replace("k", "000").replace("m", "000000").split("-");
            int lower = Integer.parseInt(parts[0].trim());
            int upper = Integer.parseInt(parts[1].trim());
            return Range.closed(lower, upper);
        } else if (value.startsWith(">")) { // Format seperti ">1k" atau ">1M"
            int lowerBound = Integer.parseInt(value.replace(">", "").replace("k", "000").replace("m", "000000").trim());
            return Range.rightOpen(lowerBound, 120); // (lowerBound, +∞)
        } else if (value.startsWith("<")) { // Format seperti "<1k" atau "<1M"
            int upperBound = Integer.parseInt(value.replace("<", "").replace("k", "000").replace("m", "000000").trim());
            return Range.leftOpen(1, upperBound); // (-∞, upperBound)
        }
        throw new IllegalArgumentException("Invalid range format: " + value);
    }

    //    ini parse range untuk price
    private Range<Integer> parseRangePrice(String value) {
        value = value.toLowerCase(); // Ubah ke lowercase untuk konsistensi
        if (value.contains("-")) { // Format seperti "1k - 10k" atau "1M - 10M"
            String[] parts = value.replace("k", "000").replace("m", "000000").split("-");
            int lower = Integer.parseInt(parts[0].trim());
            int upper = Integer.parseInt(parts[1].trim());
            return Range.closed(lower, upper);
        } else if (value.startsWith(">")) { // Format seperti ">1k" atau ">1M"
            int lowerBound = Integer.parseInt(value.replace(">", "").replace("k", "000").replace("m", "000000").trim());
            return Range.rightOpen(lowerBound, 999999999); // (lowerBound, +∞)
        } else if (value.startsWith("<")) { // Format seperti "<1k" atau "<1M"
            int upperBound = Integer.parseInt(value.replace("<", "").replace("k", "000").replace("m", "000000").trim());
            return Range.leftOpen(1, upperBound); // (-∞, upperBound)
        }
        throw new IllegalArgumentException("Invalid range format: " + value);
    }

    @Autowired
    public InfluencerRepository influencerRepository;

    @Autowired
    public UserRepository userRepository;

    @Autowired
    public BrandRepository brandRepository;

    @Autowired
    public ReviewRepository reviewRepository;

    @Autowired
    public InfluencerMediaTypeRepository influencerMediaTypeRepository;

    @Autowired
    public WalletDetailRepository walletDetailRepository;

    @Autowired
    public WalletHeaderRepository walletHeaderRepository;

    @Autowired
    public ProjectHeaderRepository projectHeaderRepository;

    @Autowired
    public ProjectDetailRepository projectDetailRepository;

    private Predicate createAgePredicate(CriteriaBuilder criteriaBuilder, Root<Influencer> root, Integer lowerAge, Integer upperAge) {
        LocalDate today = LocalDate.now();

        // Menghitung tahun kelahiran berdasarkan rentang usia
        LocalDate lowerBirthDate = today.minusYears(upperAge); // Tahun kelahiran untuk batas atas usia
        LocalDate upperBirthDate = today.minusYears(lowerAge); // Tahun kelahiran untuk batas atas usia

        // Mendapatkan dob dari root (field dalam database)
        Path<LocalDate> dobPath = root.get("dob");

        // Membandingkan dob dengan lowerBirthDate dan upperBirthDate
        Predicate lowerDatePredicate = criteriaBuilder.greaterThanOrEqualTo(dobPath, lowerBirthDate); // dob >= lowerBirthDate
        Predicate upperDatePredicate = criteriaBuilder.lessThanOrEqualTo(dobPath, upperBirthDate);   // dob <= upperBirthDate

        // Menggabungkan kedua predicate dengan AND
        return criteriaBuilder.and(lowerDatePredicate, upperDatePredicate);
    }

    private Predicate createPricePredicate(CriteriaBuilder criteriaBuilder, Join<Influencer, InfluencerMediaType> mediaTypeJoin, Integer lowerPrice, Integer upperPrice) {
        // Membandingkan harga dalam range
        Predicate lowerPricePredicate = criteriaBuilder.greaterThanOrEqualTo(mediaTypeJoin.get("price"), lowerPrice);
        Predicate upperPricePredicate = criteriaBuilder.lessThanOrEqualTo(mediaTypeJoin.get("price"), upperPrice);

        // Gabungkan predikat batas bawah dan atas dengan AND
        return criteriaBuilder.and(lowerPricePredicate, upperPricePredicate);
    }


//    public Predicate toPredicate(Root<Influencer> root, CriteriaQuery<?> query, CriteriaBuilder criteriaBuilder) {
    public Predicate toPredicate(Root<Influencer> root, CriteriaQuery<?> query, CriteriaBuilder criteriaBuilder, InfluencerFilterRequestDto influencerFilterRequestDto) {
        System.out.println("ini masuk toPredicate");
        List<Predicate> andPredicates = new ArrayList<>();
        List<Predicate> orPredicates = new ArrayList<>();

        // 1. Age Range
        if (!influencerFilterRequestDto.getAge().isEmpty()) {
            List<Predicate> agePredicates = new ArrayList<>();

            // Loop untuk setiap range followers dan buat predikatnya
            for (String range : influencerFilterRequestDto.getAge()) {
                System.out.println("masuk di for age");
                System.out.println("age per range: " + influencerFilterRequestDto.getAge());

                Range<Integer> ageRange = parseRange(range);
                System.out.println("ageRange: " + ageRange);

                // Mengambil nilai dari Optional dan mengonversinya menjadi Integer
                Integer lowerAge = ageRange.getLowerBound().getValue().orElseThrow();
                Integer upperAge = ageRange.getUpperBound().getValue().orElseThrow();

                // Menggunakan fungsi yang telah diperbarui dengan parameter rentang usia
                Predicate agePredicate = createAgePredicate(criteriaBuilder, root, lowerAge, upperAge);

                // Menambahkan predikat usia ke dalam daftar predikat
                agePredicates.add(agePredicate);

            }

            // Gabungkan predikat follower dengan OR (untuk rentang berbeda, gabungkan dengan OR)
            if (!agePredicates.isEmpty()) {
                Predicate agePredicateGroup = criteriaBuilder.or(agePredicates.toArray(new Predicate[0]));
                andPredicates.add(agePredicateGroup);
            }
        }

        // Join dengan InfluencerMediaType
        Join<Influencer, InfluencerMediaType> mediaTypeJoin = root.join("influencerMediaTypes", JoinType.LEFT);

//      2. Price Range
        if (!influencerFilterRequestDto.getPrice().isEmpty()) {
            List<Predicate> pricePredicates = new ArrayList<>();

            for (String range : influencerFilterRequestDto.getPrice()) {
                System.out.println("masuk di for age");
                System.out.println("Processing price range: " + range);
                Range<Integer> priceRange = parseRangePrice(range); // Menggunakan fungsi parseRange yang sudah ada
                System.out.println("price range: " + priceRange);
                Integer lowerPrice = priceRange.getLowerBound().getValue().orElseThrow();
                Integer upperPrice = priceRange.getUpperBound().getValue().orElseThrow();

                // Untuk setiap media type (feeds, reels, story), buat predicate
                Predicate feedsPredicate = criteriaBuilder.and(
                        criteriaBuilder.equal(mediaTypeJoin.get("mediaType").get("label"), "Feeds"),
                        createPricePredicate(criteriaBuilder, mediaTypeJoin, lowerPrice, upperPrice)
                );

                Predicate reelsPredicate = criteriaBuilder.and(
                        criteriaBuilder.equal(mediaTypeJoin.get("mediaType").get("label"), "Reels"),
                        createPricePredicate(criteriaBuilder, mediaTypeJoin, lowerPrice, upperPrice)
                );

                Predicate storyPredicate = criteriaBuilder.and(
                        criteriaBuilder.equal(mediaTypeJoin.get("mediaType").get("label"), "Story"),
                        createPricePredicate(criteriaBuilder, mediaTypeJoin, lowerPrice, upperPrice)
                );

                // Gabungkan predikat price untuk setiap media type dengan OR di dalam kurung
                Predicate mediaTypePredicateGroup = criteriaBuilder.or(feedsPredicate, reelsPredicate, storyPredicate);
                pricePredicates.add(mediaTypePredicateGroup);
            }

            // Gabungkan semua predikat price dengan OR di dalam kurung
            if (!pricePredicates.isEmpty()) {
                Predicate pricePredicateGroup = criteriaBuilder.or(pricePredicates.toArray(new Predicate[0]));
                andPredicates.add(pricePredicateGroup);
            }
        }

        // 3. Gender Filter
        if (!influencerFilterRequestDto.getGender().isEmpty()) {
            List<Predicate> genderPredicates = new ArrayList<>();

            // Loop untuk setiap gender yang dipilih
            for (Integer genderId : influencerFilterRequestDto.getGender()) {
                // Tambahkan predikat untuk gender
                Predicate genderPredicate = criteriaBuilder.equal(root.get("gender").get("id"), genderId);
                genderPredicates.add(genderPredicate);
            }

            // Gabungkan semua predikat gender dengan OR (karena sifatnya multiple select)
            if (!genderPredicates.isEmpty()) {
                Predicate genderPredicateGroup = criteriaBuilder.or(genderPredicates.toArray(new Predicate[0]));
                andPredicates.add(genderPredicateGroup);
            }
        }

        // 4. Media Type Filter
        if (!influencerFilterRequestDto.getMedia().isEmpty()) {
            List<Predicate> mediaTypePredicates = new ArrayList<>();

            // Loop untuk setiap media type yang dipilih
            for (Integer mediaTypeId : influencerFilterRequestDto.getMedia()) {
                // Tambahkan predikat untuk media type
                Predicate mediaTypePredicate = criteriaBuilder.equal(
                        root.join("influencerMediaTypes").get("mediaType").get("id"),
                        mediaTypeId
                );
                mediaTypePredicates.add(mediaTypePredicate);
            }

            // Gabungkan semua predikat media type dengan OR (karena sifatnya multiple select)
            if (!mediaTypePredicates.isEmpty()) {
                Predicate mediaTypePredicateGroup = criteriaBuilder.or(mediaTypePredicates.toArray(new Predicate[0]));
                andPredicates.add(mediaTypePredicateGroup);
            }
        }

        // 5. Location Filter
        if (!influencerFilterRequestDto.getLocation().isEmpty()) {
            List<Predicate> locationPredicates = new ArrayList<>();

            // Loop untuk setiap location ID yang dipilih
            for (Integer locationId : influencerFilterRequestDto.getLocation()) {
                // Tambahkan predikat untuk location
                Predicate locationPredicate = criteriaBuilder.equal(
                        root.join("user").join("location").get("id"),
                        locationId
                );
                locationPredicates.add(locationPredicate);
            }

            // Gabungkan semua predikat location dengan OR (karena sifatnya multiple select)
            if (!locationPredicates.isEmpty()) {
                Predicate locationPredicateGroup = criteriaBuilder.or(locationPredicates.toArray(new Predicate[0]));
                andPredicates.add(locationPredicateGroup);
            }
        }

        // 6. Rating Filter
        if (!influencerFilterRequestDto.getRating().isEmpty()) {
            List<Predicate> ratingPredicates = new ArrayList<>();

            // Loop untuk setiap rating yang dipilih
            for (String ratingInput : influencerFilterRequestDto.getRating()) {
                try {
                    // Ambil angka rating dari input (misalnya "1 star" menjadi 1)
                    Integer ratingValue = Integer.parseInt(ratingInput.split(" ")[0]);

                    // Hitung rentang rating
                    Double minRating = ratingValue.doubleValue();
                    Double maxRating = minRating + 0.99;

                    // Subquery untuk menghitung rata-rata rating influencer
                    Subquery<Double> avgRatingSubquery = query.subquery(Double.class);
                    Root<Review> reviewRoot = avgRatingSubquery.from(Review.class);
                    avgRatingSubquery.select(criteriaBuilder.avg(reviewRoot.get("rating")))
                            .where(criteriaBuilder.equal(reviewRoot.get("influencer").get("id"), root.get("id")));

                    // Predikat untuk rentang rating
                    Predicate ratingPredicate = criteriaBuilder.between(avgRatingSubquery, minRating, maxRating);
                    ratingPredicates.add(ratingPredicate);
                } catch (NumberFormatException e) {
                    System.out.println("Invalid rating format: " + ratingInput);
                }
            }

            // Gabungkan semua predikat rating dengan OR (karena sifatnya multiple select)
            if (!ratingPredicates.isEmpty()) {
                Predicate ratingPredicateGroup = criteriaBuilder.or(ratingPredicates.toArray(new Predicate[0]));
                andPredicates.add(ratingPredicateGroup);
            }
        }


        // Gabungkan semua predikat AND terlebih dahulu
        Predicate andPredicate = criteriaBuilder.and(andPredicates.toArray(new Predicate[0]));

        // Gabungkan semua predikat OR dalam kurung
        Predicate orPredicateGroup = orPredicates.isEmpty() ? null : criteriaBuilder.or(orPredicates.toArray(new Predicate[0]));

        // Gabungkan keduanya (AND dan OR)
        if (orPredicateGroup != null) {
            return criteriaBuilder.and(andPredicate, orPredicateGroup);
        }
        return andPredicate; // Jika OR kosong, hanya kembalikan AND

    }

//    ini untuk filter by followers (API IG)
    public List<Influencer> filterInfluencersByFollowers(List<Influencer> filteredInfluencers, List<String> followerRanges) {
        List<Influencer> result = new ArrayList<>();

        for (Influencer influencer : filteredInfluencers) {
            String token = influencer.getToken(); // Token Instagram
            String igid = influencer.getInstagramId();
            if (token == null || token.isEmpty()) {
                continue;
            }
            if (igid == null || igid.isEmpty()) {
                continue;
            }

            // Panggil API Instagram untuk mendapatkan jumlah followers
            int followers = getFollowersFromInstagramApi(token,igid);
            System.out.println("followers: " + followers);

            // Filter berdasarkan range followers
            boolean match = false;
            for (String range : followerRanges) {
                System.out.println("range: " + range);
                if (isFollowersInRange(followers, range)) {
                    match = true;
                    System.out.println("matchkah?" + match);
                    break;
                }
            }

            if (match) {
                result.add(influencer);
            }
        }
        return result;
    }

    // Fungsi untuk memeriksa apakah jumlah followers masuk dalam range
    private boolean isFollowersInRange(int followers, String range) {
        range = range.trim();
        if (range.startsWith(">")) {
            // Contoh "> 1000k"
            int min = parseFollowers(range.substring(1).trim());
            return followers > min;
        } else if (range.contains("-")) {
            // Contoh "10k - 100k"
            String[] parts = range.split("-");
            int min = parseFollowers(parts[0].trim());
            int max = parseFollowers(parts[1].trim());
            return followers >= min && followers <= max;
        } else {
            // Jika format tidak valid
            throw new IllegalArgumentException("Invalid range format: " + range);
        }
    }

    // Fungsi untuk mengonversi format followers ke angka
    private int parseFollowers(String value) {
        value = value.toLowerCase();
        if (value.endsWith("k")) {
            return Integer.parseInt(value.replace("k", "")) * 1000;
        } else if (value.endsWith("m")) {
            return Integer.parseInt(value.replace("m", "")) * 1000000;
        } else {
            return Integer.parseInt(value);
        }
    }

    @Autowired
    private RestTemplate restTemplate;

    @Value(value = "${base.url}")
    private String baseUrl;

    // Get followers dari API Instagram
    private int getFollowersFromInstagramApi(String token, String igid) {
        try{
//            Hit URL API Instagram
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl + "/" + igid)
                    .queryParam("fields", "followers_count")
                    .queryParam("access_token", token);

//            Ambil response
            ResponseEntity<?> response = restTemplate.getForEntity(builder.toUriString(), String.class);

//            Ubah response kedalam bentuk JSON
            ObjectMapper mapper = new ObjectMapper();
            JsonNode jsonNode = mapper.readTree(String.valueOf(response.getBody()));

//            Ambil data saja
            String data = jsonNode.get("followers_count").toString();

            Integer foll = Integer.parseInt(data);

            return foll;
        }
        catch (Exception e){
            System.out.println(e.getMessage());
            return 0;
        }
    }

    // Get followers dari API Instagram
    private String getProfilePicture(String token, String igid) {
        try{
//            Hit URL API Instagram
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl + "/" + igid)
                    .queryParam("fields", "profile_picture_url")
                    .queryParam("access_token", token);

//            Ambil response
            ResponseEntity<?> response = restTemplate.getForEntity(builder.toUriString(), String.class);

//            Ubah response kedalam bentuk JSON
            ObjectMapper mapper = new ObjectMapper();
            JsonNode jsonNode = mapper.readTree(String.valueOf(response.getBody()));

//            Ambil data saja
//            String data = jsonNode.get("profile_picture_url").toString();
            String data = jsonNode.get("profile_picture_url").asText();

            return data;
        }
        catch (Exception e){
            System.out.println(e.getMessage());
            return null;
        }
    }

    // Filter influencer berdasarkan age range yang dipilih
    public List<Influencer> filterByAudienceAge(List<Influencer> influencers, List<String> selectedAgeRanges) {
        List<Influencer> filteredInfluencers = new ArrayList<>();
        for (Influencer influencer : influencers) {
            System.out.println("influencer yang masuk looping: " + influencer.getUser().getName());
            // Ambil data demographics followers dari Instagram API (dari token influencer)
            List<AudienceAgeDto> demographics = getAudienceDemographics(influencer.getToken(),influencer.getInstagramId());
            System.out.println("demo: " + demographics);

            // Hitung total followers dari semua age range
            int totalFollowers = demographics.stream()
                    .mapToInt(AudienceAgeDto::getValue)
                    .sum();
//            totalFollowers = 30;
            System.out.println("totalFollowers: " + totalFollowers);

            // Hitung rata-rata followers dari semua age range
            double averageFollowersPerRange = totalFollowers / (double) demographics.size();
            System.out.println("averageFollowersPerRange: " + averageFollowersPerRange);

            System.out.println("selected age range " + selectedAgeRanges);
            boolean hasValidRange = demographics.stream()
                    .filter(demographic -> {
                        // Format ulang selectedAgeRanges agar sesuai dengan demographic.getAgeRange
                        List<String> formattedSelectedAgeRanges = selectedAgeRanges.stream()
                                .map(range -> {
                                    if (range.startsWith(">")) {
                                        return range.replace("> ", "") + "+";
                                    } else {
                                        return range.replace(" ", "");
                                    }
                                })
                                .collect(Collectors.toList());

                        return formattedSelectedAgeRanges.contains(demographic.getAgeRange());
                    })
                    .anyMatch(demographic -> demographic.getValue() >= averageFollowersPerRange);
            System.out.println("hasValidRange: " + hasValidRange);

            // Tambahkan influencer ke hasil jika ada age range yang memenuhi kriteria
            if (hasValidRange) {
                System.out.println("masuk valid range");
                System.out.println(influencer.getUser().getName());
                filteredInfluencers.add(influencer);
            }
        }

        return filteredInfluencers;
    }

    // Ambil data demographics audience age dari Instagram API
    private List<AudienceAgeDto> getAudienceDemographics(String token, String igid) {

        try{
            System.out.println("masuk ke hit api ig");
//            Hit URL API Instagram
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl + "/" + igid + "/insights")
                    .queryParam("metric", "follower_demographics")
                    .queryParam("period", "lifetime")
                    .queryParam("metric_type", "total_value")
                    .queryParam("breakdown", "age")
                    .queryParam("access_token", token);

            System.out.println("builder: " + builder.toUriString());

//            Ambil response
            ResponseEntity<?> response = restTemplate.getForEntity(builder.toUriString(), String.class);
            System.out.println("response: " + response);

            String responseBody = (String) response.getBody();
            System.out.println("response body: " + responseBody);

            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode root = objectMapper.readTree(responseBody);
            System.out.println("root: " + root);

            // Navigasi ke data demographics
            JsonNode breakdowns = root.path("data").get(0)
                    .path("total_value")
                    .path("breakdowns").get(0)
                    .path("results");

            System.out.println("breakdowns: " + breakdowns);

            List<AudienceAgeDto> audienceAgeDtos = new ArrayList<>();

            // Iterasi melalui setiap hasil
            for (JsonNode result : breakdowns) {
                String ageRange = result.path("dimension_values").get(0).asText();
                System.out.println("ageRange: " + ageRange);
                int value = result.path("value").asInt();
                System.out.println("value: " + value);

                // Map ke objek AudienceAge
                audienceAgeDtos.add(AudienceAgeDto.builder()
                        .ageRange(ageRange)
                        .value(value)
                        .build());
            }
            System.out.println("audience age list: " + audienceAgeDtos);
            return audienceAgeDtos;
        }
        catch (Exception e){
            System.out.println(e.getMessage());
            return null;
        }
    }

    // Filter influencer berdasarkan gender yang dipilih
    public List<Influencer> filterByGenderAudience(List<Influencer> influencers, List<Integer> selectedGenderAudiences) {
        List<Influencer> filteredByGenderAudience = new ArrayList<>();

        for (Influencer influencer : influencers) {
            // Panggil fungsi untuk mendapatkan data followers berdasarkan gender
            System.out.println("influencer yang masuk looping gender: " + influencer.getUser().getName());
            List<AudienceGenderDto> genderFollowerData = getGenderFollowerData(influencer.getToken(), influencer.getInstagramId());
            System.out.println("genderFollowerData: " + genderFollowerData);

            // Cek apakah salah satu gender yang dipilih memenuhi kriteria >= 50% dari total followers
            boolean isValid = isGenderAudienceValid(genderFollowerData, selectedGenderAudiences);
            System.out.println("isValid: " + isValid);

            // Tambahkan influencer ke hasil jika valid
            if (isValid) {
                System.out.println(influencer.getUser().getName() + " masuk valid range");
                filteredByGenderAudience.add(influencer);
            }
        }

        return filteredByGenderAudience;
    }

    private List<AudienceGenderDto> getGenderFollowerData(String token, String igid) {
        // Panggil API Instagram
        try{
            System.out.println("masuk ke hit api ig untuk gender");
//            Hit URL API Instagram
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl + "/" + igid + "/insights")
                    .queryParam("metric", "follower_demographics")
                    .queryParam("period", "lifetime")
                    .queryParam("metric_type", "total_value")
                    .queryParam("breakdown", "gender")
                    .queryParam("access_token", token);

            System.out.println("builder: " + builder.toUriString());

//            Ambil response
            ResponseEntity<?> response = restTemplate.getForEntity(builder.toUriString(), String.class);
            System.out.println("response: " + response);

            String responseBody = (String) response.getBody();
            System.out.println("response body: " + responseBody);

            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode root = objectMapper.readTree(responseBody);
            System.out.println("root: " + root);

            // Navigasi ke data demographics
            JsonNode breakdowns = root.path("data").get(0)
                    .path("total_value")
                    .path("breakdowns").get(0)
                    .path("results");

            System.out.println("breakdowns: " + breakdowns);

            List<AudienceGenderDto> audienceGenderDtos = new ArrayList<>();

            // Iterasi melalui setiap hasil
            for (JsonNode result : breakdowns) {
                String gender = result.path("dimension_values").get(0).asText();
                System.out.println("gender: " + gender);
                int value = result.path("value").asInt();
                System.out.println("value: " + value);

                // Map ke objek AudienceGender
                audienceGenderDtos.add(AudienceGenderDto.builder()
                        .gender(gender)
                        .value(value)
                        .build());
            }
            System.out.println("audience gender list: " + audienceGenderDtos);
            return audienceGenderDtos;
        }
        catch (Exception e){
            System.out.println(e.getMessage());
            return null;
        }
    }

    private boolean isGenderAudienceValid(
            List<AudienceGenderDto> genderFollowerData,
            List<Integer> selectedGenderAudiences) {

        // Map angka ke string gender
        Map<Integer, String> genderMap = Map.of(
                1, "M",  // Male
                2, "F"   // Female
        );

        // Hitung total followers yang valid (hanya "F" dan "M")
        int validTotalFollowers = genderFollowerData.stream()
                .filter(genderData -> genderData.getGender().equalsIgnoreCase("F") ||
                        genderData.getGender().equalsIgnoreCase("M"))
                .mapToInt(AudienceGenderDto::getValue)
                .sum();

        System.out.println("validTotalFollowers: " + validTotalFollowers);

        if (validTotalFollowers == 0) {
            return false; // Jika validTotalFollowers 0, otomatis tidak valid
        }

        for (Integer selectedGender : selectedGenderAudiences) {
            String genderKey = genderMap.get(selectedGender);

            if (genderKey == null) {
                continue; // Gender tidak valid, skip
            }

            // Cari nilai followers untuk gender yang dipilih
            int genderValue = genderFollowerData.stream()
                    .filter(genderData -> genderData.getGender().equalsIgnoreCase(genderKey))
                    .mapToInt(AudienceGenderDto::getValue)
                    .sum();

            // Periksa apakah nilai followers >= 40% dari total followers yang valid
            if (genderValue >= (validTotalFollowers * 0.4)) {
                return true; // Salah satu gender memenuhi kriteria
            }
        }

        return false; // Tidak ada gender yang memenuhi kriteria
    }

    public boolean hasAnyFilter(InfluencerFilterRequestDto dto) {
        return !isListEmpty(dto.getMedia()) ||
                !isListEmpty(dto.getGender()) ||
                !isListEmpty(dto.getAge()) ||
                !isListEmpty(dto.getPrice()) ||
                !isListEmpty(dto.getRating()) ||
                !isListEmpty(dto.getLocation());
    }

    private boolean isListEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }

    public static String formatPrice(String price) {
//        System.out.println("ini udah masuk di formatprice");
        if (price.isEmpty()) {
            return "";
        }
        // Format angka dengan titik (locale Indonesia)
        NumberFormat formatter = NumberFormat.getInstance(new Locale("id", "ID"));
        return formatter.format(Long.parseLong(price));
    }

//    public static String formatFollowers(int followers) {
////        System.out.println("ini lagi di format followers");
//        if (followers >= 1_000_000) {
//            return followers / 1_000_000 + "M";
//        } else if (followers >= 1_000) {
//            return followers / 1_000 + "k";
//        }
//        return String.valueOf(followers);
//    }

    public static String formatFollowers(int followers) {
        if (followers >= 1_000_000) {
            double value = followers / 1_000_000.0;
            return (value % 1 == 0) ? ((int) value + "M") : String.format("%.1fM", value);
        } else if (followers >= 1_000) {
            double value = followers / 1_000.0;
            return (value % 1 == 0) ? ((int) value + "k") : String.format("%.1fk", value);
        }
        return String.valueOf(followers);
    }

    public static Double formatRating(double rating) {
//        System.out.println("ini lagi di format rating");
        DecimalFormat decimalFormat = new DecimalFormat("#.##");
        return Double.valueOf(decimalFormat.format(rating));
    }

    public static Double formatRatingDetail(double rating) {
//        System.out.println("ini lagi di format rating");
        DecimalFormat decimalFormat = new DecimalFormat("#.#");
        return Double.valueOf(decimalFormat.format(rating));
    }

    public List<Influencer> filterInfluencersByCategory(List<Influencer> influencers, Integer categoryChosen) {
        List<Influencer> filteredInfluencers = new ArrayList<>();
        System.out.println("ini masuk di filterinfluencersbycategory");
        System.out.println("catChosen: " + categoryChosen);

        for (Influencer influencer : influencers) {
            System.out.println("infId: "+ influencer.getId());
            if (influencer.getCategories() != null) {
                for (Category category : influencer.getCategories()) {
                    System.out.println("category id: " + category.getId());
                    if (category.getId().equals(categoryChosen)) {
                        System.out.println("influencer ke " + influencer.getId() + " di add");
                        filteredInfluencers.add(influencer);
                        break; // Jika sudah cocok, hentikan loop kategori untuk influencer ini
                    }
                }
            }
        }

        return filteredInfluencers;
    }

    public List<InfluencerFilterResponseDto> filterInfluencer(InfluencerFilterRequestDto influencerFilterRequestDto, Integer brandId) {
        System.out.println(influencerFilterRequestDto);

        boolean hasFilter = hasAnyFilter(influencerFilterRequestDto);
        List<Influencer> influencers1 = new ArrayList<>();

        if(hasFilter){
            // Buat Predicate menggunakan toPredicate
            Specification<Influencer> spec = (root, query, criteriaBuilder) -> {
                return toPredicate(root, query, criteriaBuilder, influencerFilterRequestDto);
            };

            // Ambil influencer berdasarkan predikat yang telah dibuat
            influencers1 = influencerRepository.findAll(spec);

            System.out.println("spec: " + spec);
        } else{
            influencers1 = influencerRepository.findAll();
        }

        System.out.println("foll: " + influencerFilterRequestDto.getFollowers());
        System.out.println("age aud: " + influencerFilterRequestDto.getAgeAudience());

        boolean isFollowersEmpty = influencerFilterRequestDto.getFollowers() == null
                || influencerFilterRequestDto.getFollowers().isEmpty();

        List<Influencer> influencers2 = new ArrayList<>();
        influencers2 = influencers1;
//        Untuk filter by followers
        if (!isFollowersEmpty){
            System.out.println("masuk filter by foll");
            influencers2 = filterInfluencersByFollowers(influencers1, influencerFilterRequestDto.getFollowers());
        }

        boolean isAudienceAgeEmpty = influencerFilterRequestDto.getAgeAudience() == null
                || influencerFilterRequestDto.getAgeAudience().isEmpty();

        List<Influencer> influencers3 = new ArrayList<>();
        influencers3 = influencers2;
//        Untuk filter by audience age
        if (!isAudienceAgeEmpty){
            System.out.println("masuk filter by age aud");
            influencers3 = filterByAudienceAge(influencers2, influencerFilterRequestDto.getAgeAudience());
        }

        boolean isAudienceGenderEmpty = influencerFilterRequestDto.getGenderAudience() == null
                || influencerFilterRequestDto.getGenderAudience().isEmpty();

        List<Influencer> influencers4 = new ArrayList<>();
        influencers4 = influencers3;
//        Untuk filter by audience gender
        if (!isAudienceGenderEmpty){
            System.out.println("masuk filter by gender aud");
            influencers4 = filterByGenderAudience(influencers3, influencerFilterRequestDto.getGenderAudience());
        }

//        Section ini untuk bagian tab category
        List<Influencer> influencers = new ArrayList<>();
        if (!influencerFilterRequestDto.getCategoryChosen().isEmpty()){
            Integer categoryChosen = influencerFilterRequestDto.getCategoryChosen().get(0);
            System.out.println("INI MASUK KE TAB CATEGORY");
            if(categoryChosen == 0){
                influencers = influencers4;
            }
            else{
                influencers = filterInfluencersByCategory(influencers4, categoryChosen);
            }
        }else {
            influencers = influencers4;
        }


        List<InfluencerFilterResponseDto> response = new ArrayList<>();
//        return influencerRepository.findAll(spec);
        for (Influencer influencer: influencers){
            System.out.println("influencers: " + influencer.getUser().getName());

            User userBrand = userRepository.findById(brandId).orElse(null);

            Boolean isSaved;
            // brand
            if (userBrand != null && userBrand.getBrand() != null) {
                Brand brand = userBrand.getBrand();
                isSaved = isInfluencerSavedByBrand(brand.getId(), influencer.getId());
            }
            // influencer
            else {
                isSaved = false;
            }

            Boolean isBlocked = influencer.getUser().getIsBlocked();
            Boolean isActive = influencer.getIsActive();
            if (isActive && !isBlocked){
                InfluencerFilterResponseDto influencerFilterResponseDto = buildResponse(influencer, isSaved);
                // Tambahkan influencer ke response list
                response.add(influencerFilterResponseDto);
            }

//            InfluencerFilterResponseDto influencerFilterResponseDto = buildResponse(influencer, isSaved);
//
//            // Tambahkan influencer ke response list
//            response.add(influencerFilterResponseDto);
        }

        return response;
    }

    public String capitalize(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return input.substring(0, 1).toUpperCase() + input.substring(1).toLowerCase();
    }


    public List<InfluencerFilterResponseDto> sortInfluencer(List<Integer> sort, InfluencerFilterRequestDto influencerFilterRequestDto, Integer brandId) {
        System.out.println("influencerFilterRequestDto" + influencerFilterRequestDto);
        System.out.println("sort" + sort);

        boolean hasFilter = hasAnyFilter(influencerFilterRequestDto);
        List<Influencer> influencers1 = new ArrayList<>();

        if(hasFilter){
            // Buat Predicate menggunakan toPredicate
            Specification<Influencer> spec = (root, query, criteriaBuilder) -> {
                return toPredicate(root, query, criteriaBuilder, influencerFilterRequestDto);
            };

            // Ambil influencer berdasarkan predikat yang telah dibuat
            influencers1 = influencerRepository.findAll(spec);

            System.out.println("spec: " + spec);
        } else{
            influencers1 = influencerRepository.findAll();
        }

        System.out.println("foll: " + influencerFilterRequestDto.getFollowers());
        System.out.println("age aud: " + influencerFilterRequestDto.getAgeAudience());

        boolean isFollowersEmpty = influencerFilterRequestDto.getFollowers() == null
                || influencerFilterRequestDto.getFollowers().isEmpty();

        List<Influencer> influencers2 = new ArrayList<>();
        influencers2 = influencers1;
//        Untuk filter by followers
        if (!isFollowersEmpty){
            System.out.println("masuk filter by foll");
            influencers2 = filterInfluencersByFollowers(influencers1, influencerFilterRequestDto.getFollowers());
        }

        boolean isAudienceAgeEmpty = influencerFilterRequestDto.getAgeAudience() == null
                || influencerFilterRequestDto.getAgeAudience().isEmpty();

        List<Influencer> influencers3 = new ArrayList<>();
        influencers3 = influencers2;
//        Untuk filter by audience age
        if (!isAudienceAgeEmpty){
            System.out.println("masuk filter by age aud");
            influencers3 = filterByAudienceAge(influencers2, influencerFilterRequestDto.getAgeAudience());
        }

        boolean isAudienceGenderEmpty = influencerFilterRequestDto.getGenderAudience() == null
                || influencerFilterRequestDto.getGenderAudience().isEmpty();

        List<Influencer> influencers4 = new ArrayList<>();
        influencers4 = influencers3;
//        Untuk filter by audience gender
        if (!isAudienceGenderEmpty){
            System.out.println("masuk filter by gender aud");
            influencers4 = filterByGenderAudience(influencers3, influencerFilterRequestDto.getGenderAudience());
        }

//        Section ini untuk bagian tab category
        List<Influencer> influencers = new ArrayList<>();
        if (!influencerFilterRequestDto.getCategoryChosen().isEmpty()){
            Integer categoryChosen = influencerFilterRequestDto.getCategoryChosen().get(0);
            System.out.println("INI MASUK KE TAB CATEGORY");
            if(categoryChosen == 0){
                influencers = influencers4;
            }
            else{
                influencers = filterInfluencersByCategory(influencers4, categoryChosen);
            }
        }else {
            influencers = influencers4;
        }
//        ini untuk sort
        // Sort berdasarkan parameter
        if ( sort.isEmpty() || sort.get(0) == 1 ) {
            System.out.println("INI MASUK KE SORT POPULAR");
            influencers.sort(Comparator.comparing(influencer -> influencer.getProjectHeaders().size(), Comparator.reverseOrder()));
        } else if (sort.get(0) == 3) { // Sort by rating
            influencers.sort(Comparator.comparing(
                    influencer -> {
                        // Panggil repository untuk mendapatkan average rating
                        Double avgRating = influencerRepository.findAverageRatingByInfluencerId(Long.valueOf(influencer.getId()));
                        return avgRating == null ? 0.0 : avgRating; // Default nilai 0.0 jika null
                    }, Comparator.reverseOrder()));
//            influencers = influencerRepository.findAllOrderByAverageRatingDesc();
        } else if (sort.get(0) == 2) { // Sort by price
//            influencers = influencerRepository.findAllOrderByLowestPriceAsc();
            influencers.sort(Comparator.comparing(
                    influencer -> influencer.getInfluencerMediaTypes().stream()
                            .mapToDouble(mediaType -> mediaType.getPrice() != null ? mediaType.getPrice() : Double.MAX_VALUE)
                            .min()
                            .orElse(Double.MAX_VALUE)
            ));
        }

//      Mapping response
        List<InfluencerFilterResponseDto> response = new ArrayList<>();
//        return influencerRepository.findAll(spec);

        for (Influencer influencer: influencers){
            System.out.println("influencers: " + influencer.getUser().getName());
            User userBrand = userRepository.findById(brandId).orElse(null);

            Boolean isSaved;
            // brand
            if (userBrand != null && userBrand.getBrand() != null) {
                Brand brand = userBrand.getBrand();
                isSaved = isInfluencerSavedByBrand(brand.getId(), influencer.getId());
            }
            // influencer
            else {
                isSaved = false;
            }

            Boolean isBlocked = influencer.getUser().getIsBlocked();
            Boolean isActive = influencer.getIsActive();
            if (isActive && !isBlocked){
                InfluencerFilterResponseDto influencerFilterResponseDto = buildResponse(influencer, isSaved);
                // Tambahkan influencer ke response list
                response.add(influencerFilterResponseDto);
            }

//            InfluencerFilterResponseDto influencerFilterResponseDto = buildResponse(influencer, isSaved);
//
//            // Tambahkan influencer ke response list
//            response.add(influencerFilterResponseDto);
        }

        return response;
    }

    public List<InfluencerFilterResponseDto> searchInfluencers(String query, String userId) {
        System.out.println("ini masuk ke service search inf");
//        List<Influencer> influencers = influencerRepository.searchInfluencers(query.toLowerCase());
        System.out.println("query: " + query);
        System.out.println("userid: " + userId);
        List<Influencer> influencers = influencerRepository.findByUser_NameContainingIgnoreCase(query);

        // Buat list untuk menampung response
        List<InfluencerFilterResponseDto> response = new ArrayList<>();

        // Looping melalui influencer dan mapping ke DTO
        for (Influencer influencer : influencers) {
            System.out.println("influencers: " + influencer.getUser().getName());

            User userBrand = userRepository.findById(Integer.valueOf(userId)).orElse(null);

            Boolean isSaved;
            // brand
            if (userBrand != null && userBrand.getBrand() != null) {
                Brand brand = userBrand.getBrand();
                isSaved = isInfluencerSavedByBrand(brand.getId(), influencer.getId());
            }
            // influencer
            else {
                isSaved = false;
            }

            Boolean isBlocked = influencer.getUser().getIsBlocked();
            Boolean isActive = influencer.getIsActive();
            if (isActive && !isBlocked){
                InfluencerFilterResponseDto influencerFilterResponseDto = buildResponse(influencer, isSaved);
                // Tambahkan influencer ke response list
                response.add(influencerFilterResponseDto);
            }

//            InfluencerFilterResponseDto influencerFilterResponseDto = buildResponse(influencer, isSaved);
//
//            // Tambahkan influencer ke response list
//            response.add(influencerFilterResponseDto);
        }

        System.out.println("ini udah selesai build response");

        return response;
    }

    public List<InfluencerFilterResponseDto> searchInfluencersSaved(String query, String userId) {
        System.out.println("ini masuk ke service search inf");
//        List<Influencer> influencers = influencerRepository.searchInfluencers(query.toLowerCase());
        System.out.println("query: " + query);
        System.out.println("userid: " + userId);
        List<Influencer> influencers = influencerRepository.findByUser_NameContainingIgnoreCase(query);

        // Buat list untuk menampung response
        List<InfluencerFilterResponseDto> response = new ArrayList<>();

        // Looping melalui influencer dan mapping ke DTO
        for (Influencer influencer : influencers) {
            System.out.println("influencers: " + influencer.getUser().getName());

            User userBrand = userRepository.findById(Integer.valueOf(userId)).orElse(null);

            Boolean isSaved;
            // brand
            if (userBrand != null && userBrand.getBrand() != null) {
                Brand brand = userBrand.getBrand();
                isSaved = isInfluencerSavedByBrand(brand.getId(), influencer.getId());
            }
            // influencer
            else {
                isSaved = false;
            }

            if(isSaved){
                Boolean isBlocked = influencer.getUser().getIsBlocked();
                Boolean isActive = influencer.getIsActive();
                if (isActive && !isBlocked){
                    InfluencerFilterResponseDto influencerFilterResponseDto = buildResponse(influencer, isSaved);

                    // Tambahkan influencer ke response list
                    response.add(influencerFilterResponseDto);
                }

            }
        }

        System.out.println("ini udah selesai build response");

        return response;
    }


//    Dari sini masuk ke get influencer untuk page home

    public List<InfluencerFilterResponseDto> getTopInfluencer(String userId) {
        System.out.println("masuk ke get top influencer");

        // Ambil data influencer teratas, di-limiting menjadi 8 influencer
        List<Influencer> influencerData = influencerRepository.findTopInfluencers();

        // Ambil 8 influencer teratas menggunakan limit
        List<Influencer> influencers = influencerData.stream()
                .limit(8) // Membatasi hanya 8 influencer
                .collect(Collectors.toList());

        // Buat list untuk menampung response
        List<InfluencerFilterResponseDto> response = new ArrayList<>();

        // Looping melalui influencer dan mapping ke DTO
        for (Influencer influencer : influencers) {
            System.out.println("influencers: " + influencer.getUser().getName());

            User userBrand = userRepository.findById(Integer.valueOf(userId)).orElse(null);

            Boolean isSaved;
            // brand
            if (userBrand != null && userBrand.getBrand() != null) {
                Brand brand = userBrand.getBrand();
                isSaved = isInfluencerSavedByBrand(brand.getId(), influencer.getId());
            }
            // influencer
            else {
                isSaved = false;
            }

            Boolean isBlocked = influencer.getUser().getIsBlocked();
            Boolean isActive = influencer.getIsActive();
            if (isActive && !isBlocked){
                InfluencerFilterResponseDto influencerFilterResponseDto = buildResponse(influencer, isSaved);

                // Tambahkan influencer ke response list
                response.add(influencerFilterResponseDto);
            }

        }

        System.out.println("ini udah selesai build response");

        return response;
    }

//    public List<InfluencerFilterResponseDto> getRecommendationDummy(String userId) {
//        System.out.println("masuk ke get recommendation");
//
//        // Ambil data influencer teratas, di-limiting menjadi 8 influencer
//        List<InfluencerFilterResponseDto> influencerData = filterInfluencer(generateRecommendationRequest(userId), Integer.valueOf(userId));
//        System.out.println("ini generate request nya: " + generateRecommendationRequest(userId));
//        System.out.println("ini udah selesai hit filter influencer, balikannya: " + influencerData);
//
//        // Ambil 8 influencer pertama (jika jumlahnya lebih dari 8, ambil maksimal 8)
//        List<InfluencerFilterResponseDto> top8Influencers = influencerData.stream()
//                .limit(8) // Ambil 8 influencer pertama
//                .collect(Collectors.toList());
//
//        System.out.println("SELESAI GET RECOMMENDATION");
//        System.out.println("OUTPUT: " + top8Influencers);
//
//        return top8Influencers;
//    }

    public List<InfluencerFilterResponseDto> getRecommendation(String userId) {
        System.out.println("masuk ke get recommendation");

        List<Influencer> influencers = influencerRepository.findAll();
        Map<InfluencerFilterResponseDto, Double> influencerScores = new HashMap<>();

        // create dto untuk setiap influencer
        List<InfluencerFilterResponseDto> allInfluencerDto = new ArrayList<>();
        for (Influencer influencer: influencers){
            System.out.println("influencers: " + influencer.getUser().getName());

            User userBrand = userRepository.findById(Integer.valueOf(userId)).orElse(null);

            Boolean isSaved;
            // brand
            if (userBrand != null && userBrand.getBrand() != null) {
                Brand brand = userBrand.getBrand();
                isSaved = isInfluencerSavedByBrand(brand.getId(), influencer.getId());
            }
            // influencer
            else {
                isSaved = false;
            }

            Boolean isBlocked = influencer.getUser().getIsBlocked();
            Boolean isActive = influencer.getIsActive();
            if (isActive && !isBlocked){
                InfluencerFilterResponseDto influencerFilterResponseDto = buildResponse(influencer, isSaved);

                // Tambahkan influencer ke response list
                allInfluencerDto.add(influencerFilterResponseDto);
            }

        }

        // Inisialisasi skor untuk setiap influencer
        for (InfluencerFilterResponseDto influencer : allInfluencerDto) {
            influencerScores.put(influencer, 0.0);
        }
        System.out.println("Step 1: " + influencerScores);

        // Step 2: Tambah skor berdasarkan kategori brand
        List<InfluencerFilterResponseDto> influencerDataCategory = filterInfluencer(generateRecommendationCategoryRequest(userId), Integer.valueOf(userId));
        for (InfluencerFilterResponseDto influencerDto : influencerDataCategory) {
            influencerScores.put(influencerDto, influencerScores.getOrDefault(influencerDto, 0.0) + 0.4);
        }
        System.out.println("Step 2: " + influencerScores);

        // Step 3: Tambah skor berdasarkan age range
        List<InfluencerFilterResponseDto> influencerDataAge = filterInfluencer(generateRecommendationAgeRequest(userId), Integer.valueOf(userId));
        for (InfluencerFilterResponseDto influencerDto : influencerDataAge) {
            influencerScores.put(influencerDto, influencerScores.getOrDefault(influencerDto, 0.0) + 0.25);
        }
        System.out.println("Step 3: " + influencerScores);

        // Step 4: Tambah skor berdasarkan gender audience
        List<InfluencerFilterResponseDto> influencerDataGender = filterInfluencer(generateRecommendationGenderRequest(userId), Integer.valueOf(userId));
        for (InfluencerFilterResponseDto influencerDto : influencerDataGender) {
            influencerScores.put(influencerDto, influencerScores.getOrDefault(influencerDto, 0.0) + 0.2);
        }
        System.out.println("Step 4: " + influencerScores);

        // Step 5: Tambah skor berdasarkan lokasi
        List<InfluencerFilterResponseDto> influencerDataLocation = filterInfluencer(generateRecommendationLocationRequest(userId), Integer.valueOf(userId));
        for (InfluencerFilterResponseDto influencerDto : influencerDataLocation) {
            influencerScores.put(influencerDto, influencerScores.getOrDefault(influencerDto, 0.0) + 0.15);
        }
        System.out.println("Step 5: " + influencerScores);

        // Step 6: Sorting influencer berdasarkan skor tertinggi
        List<InfluencerFilterResponseDto> top8Influencers =  influencerScores.entrySet().stream()
                .sorted((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()))
                .map(Map.Entry::getKey)
                .limit(8)
                .collect(Collectors.toList());

        System.out.println("SELESAI GET RECOMMENDATION");
        System.out.println("OUTPUT: " + top8Influencers);

        return top8Influencers;
    }

//    public InfluencerFilterRequestDto generateRecommendationRequest(String userId) {
//        System.out.println("ini lagi di generate recom req untuk user " + userId);
//        // Ambil User berdasarkan userId
//        User user = userRepository.findById(Integer.valueOf(userId)).orElse(null);
//
//        if (user == null || user.getBrand() == null) {
//            // Jika user tidak ditemukan atau brand tidak terhubung
//            return null;
//        }
//
//        // Ambil brand yang terkait dengan user
//        Brand brand = user.getBrand();
//
//        // Inisialisasi DTO yang akan dikembalikan
//        InfluencerFilterRequestDto filterRequestDto = new InfluencerFilterRequestDto();
//
//        // Inisialisasi dengan list kosong jika null
//        filterRequestDto.setFollowers(filterRequestDto.getFollowers() != null ? filterRequestDto.getFollowers() : new ArrayList<>());
//        filterRequestDto.setMedia(filterRequestDto.getMedia() != null ? filterRequestDto.getMedia() : new ArrayList<>());
//        filterRequestDto.setGender(filterRequestDto.getGender() != null ? filterRequestDto.getGender() : new ArrayList<>());
//        filterRequestDto.setAge(filterRequestDto.getAge() != null ? filterRequestDto.getAge() : new ArrayList<>());
//        filterRequestDto.setPrice(filterRequestDto.getPrice() != null ? filterRequestDto.getPrice() : new ArrayList<>());
//        filterRequestDto.setRating(filterRequestDto.getRating() != null ? filterRequestDto.getRating() : new ArrayList<>());
//        filterRequestDto.setLocation(filterRequestDto.getLocation() != null ? filterRequestDto.getLocation() : new ArrayList<>());
//        filterRequestDto.setGenderAudience(filterRequestDto.getGenderAudience() != null ? filterRequestDto.getGenderAudience() : new ArrayList<>());
//        filterRequestDto.setAgeAudience(filterRequestDto.getAgeAudience() != null ? filterRequestDto.getAgeAudience() : new ArrayList<>());
//        filterRequestDto.setCategoryChosen(filterRequestDto.getCategoryChosen() != null ? filterRequestDto.getCategoryChosen() : new ArrayList<>());
//
//        // Mengisi location (id location dari brand)
//        List<Integer> locationIds = brand.getLocations().stream()
//                .map(Location::getId) // Ambil ID dari masing-masing location
//                .collect(Collectors.toList());
//        filterRequestDto.setLocation(locationIds);
//
//        // Mengisi genderAudience (id gender dari brand)
//        List<Integer> genderAudienceIds = brand.getGenders().stream()
//                .map(Gender::getId) // Ambil ID dari masing-masing gender
//                .collect(Collectors.toList());
//        filterRequestDto.setGenderAudience(genderAudienceIds);
//
//        // Mengisi ageAudience (label dari age yang ditargetkan oleh brand)
//        List<String> ageAudienceLabels = brand.getAges().stream()
//                .map(Age::getLabel) // Ambil label dari masing-masing age
//                .collect(Collectors.toList());
//        filterRequestDto.setAgeAudience(ageAudienceLabels);
//
//        System.out.println("filterRequestDto" + filterRequestDto);
//
//        // Kembalikan filterRequestDto yang sudah terisi
//        return filterRequestDto;
//    }

    public InfluencerFilterRequestDto generateRecommendationCategoryRequest(String userId) {
        System.out.println("ini lagi di generate recom req untuk user " + userId);
        // Ambil User berdasarkan userId
        User user = userRepository.findById(Integer.valueOf(userId)).orElse(null);

        if (user == null || user.getBrand() == null) {
            // Jika user tidak ditemukan atau brand tidak terhubung
            return null;
        }

        // Ambil brand yang terkait dengan user
        Brand brand = user.getBrand();

        // Inisialisasi DTO yang akan dikembalikan
        InfluencerFilterRequestDto filterRequestDto = new InfluencerFilterRequestDto();

        // Inisialisasi dengan list kosong jika null
        filterRequestDto.setFollowers(filterRequestDto.getFollowers() != null ? filterRequestDto.getFollowers() : new ArrayList<>());
        filterRequestDto.setMedia(filterRequestDto.getMedia() != null ? filterRequestDto.getMedia() : new ArrayList<>());
        filterRequestDto.setGender(filterRequestDto.getGender() != null ? filterRequestDto.getGender() : new ArrayList<>());
        filterRequestDto.setAge(filterRequestDto.getAge() != null ? filterRequestDto.getAge() : new ArrayList<>());
        filterRequestDto.setPrice(filterRequestDto.getPrice() != null ? filterRequestDto.getPrice() : new ArrayList<>());
        filterRequestDto.setRating(filterRequestDto.getRating() != null ? filterRequestDto.getRating() : new ArrayList<>());
        filterRequestDto.setLocation(filterRequestDto.getLocation() != null ? filterRequestDto.getLocation() : new ArrayList<>());
        filterRequestDto.setGenderAudience(filterRequestDto.getGenderAudience() != null ? filterRequestDto.getGenderAudience() : new ArrayList<>());
        filterRequestDto.setAgeAudience(filterRequestDto.getAgeAudience() != null ? filterRequestDto.getAgeAudience() : new ArrayList<>());
        filterRequestDto.setCategoryChosen(filterRequestDto.getCategoryChosen() != null ? filterRequestDto.getCategoryChosen() : new ArrayList<>());

        // Mengisi category (id category dari brand)
        List<Integer> categoryId = Collections.singletonList(brand.getCategory().getId());
        filterRequestDto.setCategoryChosen(categoryId);

        System.out.println("filterRequestDto" + filterRequestDto);

        // Kembalikan filterRequestDto yang sudah terisi
        return filterRequestDto;
    }

    public InfluencerFilterRequestDto generateRecommendationAgeRequest(String userId) {
        System.out.println("ini lagi di generate recom req untuk user " + userId);
        // Ambil User berdasarkan userId
        User user = userRepository.findById(Integer.valueOf(userId)).orElse(null);

        if (user == null || user.getBrand() == null) {
            // Jika user tidak ditemukan atau brand tidak terhubung
            return null;
        }

        // Ambil brand yang terkait dengan user
        Brand brand = user.getBrand();

        // Inisialisasi DTO yang akan dikembalikan
        InfluencerFilterRequestDto filterRequestDto = new InfluencerFilterRequestDto();

        // Inisialisasi dengan list kosong jika null
        filterRequestDto.setFollowers(filterRequestDto.getFollowers() != null ? filterRequestDto.getFollowers() : new ArrayList<>());
        filterRequestDto.setMedia(filterRequestDto.getMedia() != null ? filterRequestDto.getMedia() : new ArrayList<>());
        filterRequestDto.setGender(filterRequestDto.getGender() != null ? filterRequestDto.getGender() : new ArrayList<>());
        filterRequestDto.setAge(filterRequestDto.getAge() != null ? filterRequestDto.getAge() : new ArrayList<>());
        filterRequestDto.setPrice(filterRequestDto.getPrice() != null ? filterRequestDto.getPrice() : new ArrayList<>());
        filterRequestDto.setRating(filterRequestDto.getRating() != null ? filterRequestDto.getRating() : new ArrayList<>());
        filterRequestDto.setLocation(filterRequestDto.getLocation() != null ? filterRequestDto.getLocation() : new ArrayList<>());
        filterRequestDto.setGenderAudience(filterRequestDto.getGenderAudience() != null ? filterRequestDto.getGenderAudience() : new ArrayList<>());
        filterRequestDto.setAgeAudience(filterRequestDto.getAgeAudience() != null ? filterRequestDto.getAgeAudience() : new ArrayList<>());
        filterRequestDto.setCategoryChosen(filterRequestDto.getCategoryChosen() != null ? filterRequestDto.getCategoryChosen() : new ArrayList<>());

        // Mengisi ageAudience (label dari age yang ditargetkan oleh brand)
        List<String> ageAudienceLabels = brand.getAges().stream()
                .map(Age::getLabel) // Ambil label dari masing-masing age
                .collect(Collectors.toList());
        filterRequestDto.setAgeAudience(ageAudienceLabels);

        System.out.println("filterRequestDto" + filterRequestDto);

        // Kembalikan filterRequestDto yang sudah terisi
        return filterRequestDto;
    }

    public InfluencerFilterRequestDto generateRecommendationGenderRequest(String userId) {
        System.out.println("ini lagi di generate recom req untuk user " + userId);
        // Ambil User berdasarkan userId
        User user = userRepository.findById(Integer.valueOf(userId)).orElse(null);

        if (user == null || user.getBrand() == null) {
            // Jika user tidak ditemukan atau brand tidak terhubung
            return null;
        }

        // Ambil brand yang terkait dengan user
        Brand brand = user.getBrand();

        // Inisialisasi DTO yang akan dikembalikan
        InfluencerFilterRequestDto filterRequestDto = new InfluencerFilterRequestDto();

        // Inisialisasi dengan list kosong jika null
        filterRequestDto.setFollowers(filterRequestDto.getFollowers() != null ? filterRequestDto.getFollowers() : new ArrayList<>());
        filterRequestDto.setMedia(filterRequestDto.getMedia() != null ? filterRequestDto.getMedia() : new ArrayList<>());
        filterRequestDto.setGender(filterRequestDto.getGender() != null ? filterRequestDto.getGender() : new ArrayList<>());
        filterRequestDto.setAge(filterRequestDto.getAge() != null ? filterRequestDto.getAge() : new ArrayList<>());
        filterRequestDto.setPrice(filterRequestDto.getPrice() != null ? filterRequestDto.getPrice() : new ArrayList<>());
        filterRequestDto.setRating(filterRequestDto.getRating() != null ? filterRequestDto.getRating() : new ArrayList<>());
        filterRequestDto.setLocation(filterRequestDto.getLocation() != null ? filterRequestDto.getLocation() : new ArrayList<>());
        filterRequestDto.setGenderAudience(filterRequestDto.getGenderAudience() != null ? filterRequestDto.getGenderAudience() : new ArrayList<>());
        filterRequestDto.setAgeAudience(filterRequestDto.getAgeAudience() != null ? filterRequestDto.getAgeAudience() : new ArrayList<>());
        filterRequestDto.setCategoryChosen(filterRequestDto.getCategoryChosen() != null ? filterRequestDto.getCategoryChosen() : new ArrayList<>());

        // Mengisi genderAudience (id gender dari brand)
        List<Integer> genderAudienceIds = brand.getGenders().stream()
                .map(Gender::getId) // Ambil ID dari masing-masing gender
                .collect(Collectors.toList());
        filterRequestDto.setGenderAudience(genderAudienceIds);

        System.out.println("filterRequestDto" + filterRequestDto);

        // Kembalikan filterRequestDto yang sudah terisi
        return filterRequestDto;
    }

    public InfluencerFilterRequestDto generateRecommendationLocationRequest(String userId) {
        System.out.println("ini lagi di generate recom location req untuk user " + userId);
        // Ambil User berdasarkan userId
        User user = userRepository.findById(Integer.valueOf(userId)).orElse(null);

        if (user == null || user.getBrand() == null) {
            // Jika user tidak ditemukan atau brand tidak terhubung
            return null;
        }

        // Ambil brand yang terkait dengan user
        Brand brand = user.getBrand();

        // Inisialisasi DTO yang akan dikembalikan
        InfluencerFilterRequestDto filterRequestDto = new InfluencerFilterRequestDto();

        // Inisialisasi dengan list kosong jika null
        filterRequestDto.setFollowers(filterRequestDto.getFollowers() != null ? filterRequestDto.getFollowers() : new ArrayList<>());
        filterRequestDto.setMedia(filterRequestDto.getMedia() != null ? filterRequestDto.getMedia() : new ArrayList<>());
        filterRequestDto.setGender(filterRequestDto.getGender() != null ? filterRequestDto.getGender() : new ArrayList<>());
        filterRequestDto.setAge(filterRequestDto.getAge() != null ? filterRequestDto.getAge() : new ArrayList<>());
        filterRequestDto.setPrice(filterRequestDto.getPrice() != null ? filterRequestDto.getPrice() : new ArrayList<>());
        filterRequestDto.setRating(filterRequestDto.getRating() != null ? filterRequestDto.getRating() : new ArrayList<>());
        filterRequestDto.setLocation(filterRequestDto.getLocation() != null ? filterRequestDto.getLocation() : new ArrayList<>());
        filterRequestDto.setGenderAudience(filterRequestDto.getGenderAudience() != null ? filterRequestDto.getGenderAudience() : new ArrayList<>());
        filterRequestDto.setAgeAudience(filterRequestDto.getAgeAudience() != null ? filterRequestDto.getAgeAudience() : new ArrayList<>());
        filterRequestDto.setCategoryChosen(filterRequestDto.getCategoryChosen() != null ? filterRequestDto.getCategoryChosen() : new ArrayList<>());

        // Mengisi location (id location dari brand)
        List<Integer> locationIds = brand.getLocations().stream()
                .map(Location::getId) // Ambil ID dari masing-masing location
                .collect(Collectors.toList());
        filterRequestDto.setLocation(locationIds);

        System.out.println("filterRequestDto" + filterRequestDto);

        // Kembalikan filterRequestDto yang sudah terisi
        return filterRequestDto;
    }

    public InfluencerFilterResponseDto buildResponse(Influencer influencer, Boolean isSaved){

        Double averageRating = influencerRepository.findAverageRatingByInfluencerId(Long.valueOf(influencer.getId()));
        Integer totalReviews = influencerRepository.findTotalReviewsByInfluencerId(Long.valueOf(influencer.getId()));


        if (averageRating == null) {
            averageRating = 0.0; // Default jika tidak ada review
        }

        if (totalReviews == null) {
            totalReviews = 0; // Default jika tidak ada review
        }

        List<Category> categories = influencer.getCategories();
        List<Map<String,Object>> categoryDto = new ArrayList<>();

        String feedsPrice = "";
        String reelsPrice = "";
        String storyPrice = "";
        List<InfluencerMediaType> mediaTypes = influencer.getInfluencerMediaTypes();
        for(InfluencerMediaType mediaType: mediaTypes){
            if(mediaType.getMediaType().getLabel().equalsIgnoreCase("feeds")){
                feedsPrice = mediaType.getPrice().toString();
            }else if(mediaType.getMediaType().getLabel().equalsIgnoreCase("reels")){
                reelsPrice = mediaType.getPrice().toString();
            }else if(mediaType.getMediaType().getLabel().equalsIgnoreCase("story")){
                storyPrice = mediaType.getPrice().toString();
            }
        }

        // Cari harga termurah
        int minPrice = Integer.MAX_VALUE; // Nilai awal sebagai infinity

        if (!feedsPrice.isEmpty()) {
            minPrice = Math.min(minPrice, Integer.parseInt(feedsPrice));
        }
        if (!reelsPrice.isEmpty()) {
            minPrice = Math.min(minPrice, Integer.parseInt(reelsPrice));
        }
        if (!storyPrice.isEmpty()) {
            minPrice = Math.min(minPrice, Integer.parseInt(storyPrice));
        }

        // Validasi jika tidak ada harga yang ditemukan
        if (minPrice == Integer.MAX_VALUE) {
            minPrice = 0; // Tidak ada harga yang ditemukan
        }

        System.out.println("Harga termurah: " + minPrice);

        for (Category category: categories){
            System.out.println("ini di bagian category");
//                System.out.println("category: " + category);
            Map<String,Object> newMap = new HashMap<>();
            newMap.put("id", category.getId());
            newMap.put("label", category.getLabel());
            categoryDto.add(newMap);
        }

        // Bangun InfluencerFilterResponseDto untuk setiap influencer
        InfluencerFilterResponseDto influencerFilterResponseDto = InfluencerFilterResponseDto.builder()
                .id(influencer.getUser().getId())
                .influencerId(influencer.getId())
                .name(influencer.getUser().getName())
                .email(influencer.getUser().getEmail())
                .location(capitalize(influencer.getUser().getLocation().getLabel()))
                .phone(influencer.getUser().getPhone())
                .gender(influencer.getGender().getLabel())
                .dob(influencer.getDob().toString())
                .feedsprice(formatPrice(feedsPrice)) // Pastikan feedsPrice sudah didefinisikan
                .reelsprice(formatPrice(reelsPrice)) // Pastikan reelsPrice sudah didefinisikan
                .storyprice(formatPrice(storyPrice)) // Pastikan storyPrice sudah didefinisikan
                .category(categoryDto) // Pastikan categoryDto sudah didefinisikan
                .usertype(influencer.getUser().getUserType())
                .instagramid(influencer.getInstagramId())
                .isactive(influencer.getIsActive())
                .token(influencer.getToken())
                .followers(formatFollowers(getFollowersFromInstagramApi(influencer.getToken(), influencer.getInstagramId())))
                .rating(formatRating(averageRating)) // Pastikan averageRating sudah didefinisikan
                .minprice(formatPrice(String.valueOf(minPrice))) // Pastikan minPrice sudah didefinisikan
                .totalreview(formatFollowers(totalReviews)) // Pastikan totalReviews sudah didefinisikan
                .profilepicture(getProfilePicture(influencer.getToken(), influencer.getInstagramId()))
                .issaved(isSaved)
                .isblocked(influencer.getUser().getIsBlocked())
                .build();

        return influencerFilterResponseDto;
    }

    public String saveInfluencer(Integer brandUserId, Integer influencerUserId){
        // Ambil brand berdasarkan brandUserId
        User userBrand = userRepository.findById(brandUserId).orElse(null);
        Brand brand = userBrand.getBrand();
        if (userBrand == null) {
            return "Brand user id not found";
        }
        System.out.println("brand: " + brand.getUser().getName());

        // Ambil influencer berdasarkan influencerUserId
        User userInfluencer = userRepository.findById(influencerUserId).orElse(null);
        Influencer influencer = userInfluencer.getInfluencer();
        if (userInfluencer == null) {
            return "Influencer user id not found";
        }
        System.out.println("influencer: " + influencer.getUser().getName());

        // Cek apakah influencer sudah ada dalam list brand
        if (!brand.getInfluencers().contains(influencer)) {
//            ini masuk ke if gaada di list
            brand.getInfluencers().add(influencer);

            // Simpan perubahan ke database
            brandRepository.save(brand);

            return "Influencer saved successfully";
        } else {
            return "Influencer already saved";
        }
    }

    public String unsaveInfluencer(Integer brandUserId, Integer influencerUserId){
        // Ambil brand berdasarkan brandUserId
        User userBrand = userRepository.findById(brandUserId).orElse(null);
        Brand brand = userBrand.getBrand();
        if (userBrand == null) {
            return "Brand user id not found";
        }
        System.out.println("brand: " + brand.getUser().getName());

        // Ambil influencer berdasarkan influencerUserId
        User userInfluencer = userRepository.findById(influencerUserId).orElse(null);
        Influencer influencer = userInfluencer.getInfluencer();
        if (userInfluencer == null) {
            return "Influencer user id not found";
        }
        System.out.println("influencer: " + influencer.getUser().getName());


        // Cek apakah influencer ada dalam list brand
        if (brand.getInfluencers().contains(influencer)) {
            // Hapus influencer dari list
            brand.getInfluencers().remove(influencer);

            // Simpan perubahan ke database
            brandRepository.save(brand);

            return "Influencer unsaved successfully";
        } else {
            return "Influencer is not in saved list";
        }
    }

    public boolean isInfluencerSavedByBrand(Integer brandId, Integer influencerId) {
        return influencerRepository.isInfluencerSaved(brandId, influencerId);
    }

    public List<InfluencerFilterResponseDto> filterInfluencerSaved(InfluencerFilterRequestDto influencerFilterRequestDto, Integer brandId) {
        System.out.println(influencerFilterRequestDto);

        boolean hasFilter = hasAnyFilter(influencerFilterRequestDto);
        List<Influencer> influencers1 = new ArrayList<>();

        if(hasFilter){
            // Buat Predicate menggunakan toPredicate
            Specification<Influencer> spec = (root, query, criteriaBuilder) -> {
                return toPredicate(root, query, criteriaBuilder, influencerFilterRequestDto);
            };

            // Ambil influencer berdasarkan predikat yang telah dibuat
            influencers1 = influencerRepository.findAll(spec);

            System.out.println("spec: " + spec);
        } else{
            influencers1 = influencerRepository.findAll();
        }

        System.out.println("foll: " + influencerFilterRequestDto.getFollowers());
        System.out.println("age aud: " + influencerFilterRequestDto.getAgeAudience());

        boolean isFollowersEmpty = influencerFilterRequestDto.getFollowers() == null
                || influencerFilterRequestDto.getFollowers().isEmpty();

        List<Influencer> influencers2 = new ArrayList<>();
        influencers2 = influencers1;
//        Untuk filter by followers
        if (!isFollowersEmpty){
            System.out.println("masuk filter by foll");
            influencers2 = filterInfluencersByFollowers(influencers1, influencerFilterRequestDto.getFollowers());
        }

        boolean isAudienceAgeEmpty = influencerFilterRequestDto.getAgeAudience() == null
                || influencerFilterRequestDto.getAgeAudience().isEmpty();

        List<Influencer> influencers3 = new ArrayList<>();
        influencers3 = influencers2;
//        Untuk filter by audience age
        if (!isAudienceAgeEmpty){
            System.out.println("masuk filter by age aud");
            influencers3 = filterByAudienceAge(influencers2, influencerFilterRequestDto.getAgeAudience());
        }

        boolean isAudienceGenderEmpty = influencerFilterRequestDto.getGenderAudience() == null
                || influencerFilterRequestDto.getGenderAudience().isEmpty();

//        List<Influencer> influencers = new ArrayList<>();
//        influencers = influencers3;
////        Untuk filter by audience gender
//        if (!isAudienceGenderEmpty){
//            System.out.println("masuk filter by gender aud");
//            influencers = filterByGenderAudience(influencers3, influencerFilterRequestDto.getGenderAudience());
//        }

        List<Influencer> influencers4 = new ArrayList<>();
        influencers4 = influencers3;
//        Untuk filter by audience gender
        if (!isAudienceGenderEmpty){
            System.out.println("masuk filter by gender aud");
            influencers4 = filterByGenderAudience(influencers3, influencerFilterRequestDto.getGenderAudience());
        }

//        Section ini untuk bagian tab category
        List<Influencer> influencers = new ArrayList<>();
        if (!influencerFilterRequestDto.getCategoryChosen().isEmpty()){
            Integer categoryChosen = influencerFilterRequestDto.getCategoryChosen().get(0);
            System.out.println("INI MASUK KE TAB CATEGORY");
            if(categoryChosen == 0){
                influencers = influencers4;
            }
            else{
                influencers = filterInfluencersByCategory(influencers4, categoryChosen);
            }
        }else {
            influencers = influencers4;
        }


        List<InfluencerFilterResponseDto> response = new ArrayList<>();
//        return influencerRepository.findAll(spec);
        for (Influencer influencer: influencers){
            System.out.println("influencers: " + influencer.getUser().getName());

            User userBrand = userRepository.findById(brandId).orElse(null);

            Boolean isSaved;
            // brand
            if (userBrand != null && userBrand.getBrand() != null) {
                Brand brand = userBrand.getBrand();
                isSaved = isInfluencerSavedByBrand(brand.getId(), influencer.getId());
            }
            // influencer
            else {
                isSaved = false;
            }

            if(isSaved){
                Boolean isBlocked = influencer.getUser().getIsBlocked();
                Boolean isActive = influencer.getIsActive();
                if (isActive && !isBlocked){
                    InfluencerFilterResponseDto influencerFilterResponseDto = buildResponse(influencer, isSaved);
                    // Tambahkan influencer ke response list
                    response.add(influencerFilterResponseDto);
                }
            }

        }

        return response;
    }

    public List<InfluencerFilterResponseDto> sortInfluencerSaved(List<Integer> sort, InfluencerFilterRequestDto influencerFilterRequestDto, Integer brandId) {
        System.out.println("influencerFilterRequestDto" + influencerFilterRequestDto);
        System.out.println("sort" + sort);

        boolean hasFilter = hasAnyFilter(influencerFilterRequestDto);
        List<Influencer> influencers1 = new ArrayList<>();

        if(hasFilter){
            // Buat Predicate menggunakan toPredicate
            Specification<Influencer> spec = (root, query, criteriaBuilder) -> {
                return toPredicate(root, query, criteriaBuilder, influencerFilterRequestDto);
            };

            // Ambil influencer berdasarkan predikat yang telah dibuat
            influencers1 = influencerRepository.findAll(spec);

            System.out.println("spec: " + spec);
        } else{
            influencers1 = influencerRepository.findAll();
        }

        System.out.println("foll: " + influencerFilterRequestDto.getFollowers());
        System.out.println("age aud: " + influencerFilterRequestDto.getAgeAudience());

        boolean isFollowersEmpty = influencerFilterRequestDto.getFollowers() == null
                || influencerFilterRequestDto.getFollowers().isEmpty();

        List<Influencer> influencers2 = new ArrayList<>();
        influencers2 = influencers1;
//        Untuk filter by followers
        if (!isFollowersEmpty){
            System.out.println("masuk filter by foll");
            influencers2 = filterInfluencersByFollowers(influencers1, influencerFilterRequestDto.getFollowers());
        }

        boolean isAudienceAgeEmpty = influencerFilterRequestDto.getAgeAudience() == null
                || influencerFilterRequestDto.getAgeAudience().isEmpty();

        List<Influencer> influencers3 = new ArrayList<>();
        influencers3 = influencers2;
//        Untuk filter by audience age
        if (!isAudienceAgeEmpty){
            System.out.println("masuk filter by age aud");
            influencers3 = filterByAudienceAge(influencers2, influencerFilterRequestDto.getAgeAudience());
        }

        boolean isAudienceGenderEmpty = influencerFilterRequestDto.getGenderAudience() == null
                || influencerFilterRequestDto.getGenderAudience().isEmpty();

//        List<Influencer> influencers = new ArrayList<>();
//        influencers = influencers3;
////        Untuk filter by audience gender
//        if (!isAudienceGenderEmpty){
//            System.out.println("masuk filter by gender aud");
//            influencers = filterByGenderAudience(influencers3, influencerFilterRequestDto.getGenderAudience());
//        }

        List<Influencer> influencers4 = new ArrayList<>();
        influencers4 = influencers3;
//        Untuk filter by audience gender
        if (!isAudienceGenderEmpty){
            System.out.println("masuk filter by gender aud");
            influencers4 = filterByGenderAudience(influencers3, influencerFilterRequestDto.getGenderAudience());
        }

//        Section ini untuk bagian tab category
        List<Influencer> influencers = new ArrayList<>();
        if (!influencerFilterRequestDto.getCategoryChosen().isEmpty()){
            Integer categoryChosen = influencerFilterRequestDto.getCategoryChosen().get(0);
            System.out.println("INI MASUK KE TAB CATEGORY");
            if(categoryChosen == 0){
                influencers = influencers4;
            }
            else{
                influencers = filterInfluencersByCategory(influencers4, categoryChosen);
            }
        }else {
            influencers = influencers4;
        }


//        ini untuk sort
        // Sort berdasarkan parameter
//        if (sort.isEmpty() || sort.get(0) == 1 ) {
//            System.out.println("INI MASUK KE SORT POPULAR");
//            influencers.sort(Comparator.comparing(influencer -> influencer.getProjectHeaders().size(), Comparator.reverseOrder()));
//        } else if (sort.get(0) == 3) { // Sort by rating
//            influencers = influencerRepository.findAllOrderByAverageRatingDesc();
//        } else if (sort.get(0) == 2) { // Sort by price
//            influencers = influencerRepository.findAllOrderByLowestPriceAsc();
//        }
        // Sort berdasarkan parameter
        if ( sort.isEmpty() || sort.get(0) == 1 ) {
            System.out.println("INI MASUK KE SORT POPULAR");
            influencers.sort(Comparator.comparing(influencer -> influencer.getProjectHeaders().size(), Comparator.reverseOrder()));
        } else if (sort.get(0) == 3) { // Sort by rating
            influencers.sort(Comparator.comparing(
                    influencer -> {
                        // Panggil repository untuk mendapatkan average rating
                        Double avgRating = influencerRepository.findAverageRatingByInfluencerId(Long.valueOf(influencer.getId()));
                        return avgRating == null ? 0.0 : avgRating; // Default nilai 0.0 jika null
                    }, Comparator.reverseOrder()));
//            influencers = influencerRepository.findAllOrderByAverageRatingDesc();
        } else if (sort.get(0) == 2) { // Sort by price
//            influencers = influencerRepository.findAllOrderByLowestPriceAsc();
            influencers.sort(Comparator.comparing(
                    influencer -> influencer.getInfluencerMediaTypes().stream()
                            .mapToDouble(mediaType -> mediaType.getPrice() != null ? mediaType.getPrice() : Double.MAX_VALUE)
                            .min()
                            .orElse(Double.MAX_VALUE)
            ));
        }

//      Mapping response
        List<InfluencerFilterResponseDto> response = new ArrayList<>();
//        return influencerRepository.findAll(spec);
        for (Influencer influencer: influencers){
            System.out.println("influencers: " + influencer.getUser().getName());


            User userBrand = userRepository.findById(brandId).orElse(null);

            Boolean isSaved;
            // brand
            if (userBrand != null && userBrand.getBrand() != null) {
                Brand brand = userBrand.getBrand();
                isSaved = isInfluencerSavedByBrand(brand.getId(), influencer.getId());
            }
            // influencer
            else {
                isSaved = false;
            }

            if(isSaved){
                Boolean isBlocked = influencer.getUser().getIsBlocked();
                Boolean isActive = influencer.getIsActive();
                if (isActive && !isBlocked){
                    InfluencerFilterResponseDto influencerFilterResponseDto = buildResponse(influencer, isSaved);
                    // Tambahkan influencer ke response list
                    response.add(influencerFilterResponseDto);
                }
            }
        }
        return response;
    }

    public InfluencerDetailResponseDto detailInfluencer(Integer influencerId, Integer userId) {
        Influencer influencer = influencerRepository.findById(influencerId).orElse(null);
        System.out.println("influencer: " + influencer.getUser().getName());

        User userBrand = userRepository.findById(userId).orElse(null);

        Boolean isSaved;
        // brand
        if (userBrand != null && userBrand.getBrand() != null) {
            Brand brand = userBrand.getBrand();
            isSaved = isInfluencerSavedByBrand(brand.getId(), influencer.getId());
        }
        // influencer
        else {
            isSaved = false;
        }

        InfluencerDetailResponseDto influencerFilterResponseDto = buildDetailResponse(influencer, isSaved);

        return influencerFilterResponseDto;
    }

    public InfluencerHomeDto detailHomeInfluencer(Integer userId) {
//        Influencer influencer = influencerRepository.findById(userId).orElse(null);
        User user = userRepository.findById(userId).orElse(null);
        Influencer influencer = influencerRepository.findByUser(user);
        System.out.println("detail home influencer: " + influencer.getUser().getName());

        InfluencerHomeDto influencerHomeResponseDto = buildDetailHomeResponse(influencer);

        return influencerHomeResponseDto;
    }

    public InfluencerDetailResponseDto detailInfluencerForProfile(Integer influencerId) {
        Influencer influencer = influencerRepository.findById(influencerId).orElse(null);

        InfluencerDetailResponseDto influencerFilterResponseDto = buildDetailResponse(influencer, false);

        return influencerFilterResponseDto;
    }

    public StoryDetailDto fetchStoryDetails(String storyId, String token) {
        try{
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl + "/" + storyId + "/insights")
                    .queryParam("metric", "shares,views")
                    .queryParam("period", "lifetime")
                    .queryParam("metric_type", "total_value")
                    .queryParam("access_token", token);

//            Ambil response
            ResponseEntity<?> response = restTemplate.getForEntity(builder.toUriString(), String.class);

//            Ubah response kedalam bentuk JSON
            ObjectMapper mapper = new ObjectMapper();
            JsonNode jsonNode = mapper.readTree(String.valueOf(response.getBody()));

            // Inisialisasi nilai default
            int shareCount = 0, viewCount = 0;

            // Cek apakah ada data
            if (jsonNode.has("data") && jsonNode.get("data").isArray()) {
                for (JsonNode dataNode : jsonNode.get("data")) {
                    String metricName = dataNode.get("name").asText();
                    int value = dataNode.get("values").get(0).get("value").asInt();

                    // Cek tipe metrik dan assign ke variabel yang sesuai
                    switch (metricName) {
                        case "views":
                            viewCount = value;
                            break;
                        case "shares":
                            shareCount = value;
                            break;
                    }
                }
            }
//            sekarang mau ambil media type dll
            UriComponentsBuilder builder2 = UriComponentsBuilder.fromUriString(baseUrl + "/" + storyId)
                    .queryParam("fields", "media_type,media_product_type,media_url,permalink,timestamp,thumbnail_url")
                    .queryParam("access_token", token);

//            Ambil response
            ResponseEntity<?> response2 = restTemplate.getForEntity(builder2.toUriString(), String.class);

//            Ubah response kedalam bentuk JSON
            ObjectMapper mapper2 = new ObjectMapper();
            JsonNode jsonNode2 = mapper2.readTree(String.valueOf(response2.getBody()));

            String mediaType = jsonNode2.get("media_type").asText();
            String mediaProductType = jsonNode2.get("media_product_type").asText();
            String mediaUrl = jsonNode2.get("media_url").asText();
            String permalink = jsonNode2.get("permalink").asText();
            String timestamp = jsonNode2.get("timestamp").asText();

            String thumbnailUrl = mediaUrl;
            if (mediaType.equalsIgnoreCase("video")){
                thumbnailUrl = jsonNode2.get("thumbnail_url").asText();
            }

            // Return DTO yang sudah diisi
            return StoryDetailDto.builder()
                    .id(storyId)
                    .viewcount(viewCount)
                    .sharecount(shareCount)
                    .mediatype(mediaType)
                    .mediaproducttype(mediaProductType)
                    .mediaurl(mediaUrl)
                    .permalink(permalink)
                    .timestamp(timestamp)
                    .thumbnailurl(thumbnailUrl)
                    .build();
        }
        catch (HttpClientErrorException.BadRequest e){
            try {
                // Parsing response error dari API
                ObjectMapper mapper = new ObjectMapper();
                JsonNode errorNode = mapper.readTree(e.getResponseBodyAsString());

                if (errorNode.has("error")) {
                    JsonNode errorDetails = errorNode.get("error");
                    int errorCode = errorDetails.get("code").asInt();
                    String errorMessage = errorDetails.has("message") ? errorDetails.get("message").asText() : "";

                    // Handle error khusus jika media diposting sebelum akun menjadi business account
                    if (errorCode == 10 && errorMessage.contains("Not enough viewers for the media to show insights")) {
                        System.out.println("⚠️ Skipping media " + storyId + " because it does not have enough viewers for insights.");
                        return null;
                    }
                }
            } catch (Exception parseError) {
                System.out.println("❌ Error parsing error response: " + parseError.getMessage());
            }

            return null; // Return null jika terjadi error
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("error di fetchstorydetail");
            return null;

        }
    }

    public MediaDetailDto fetchMediaDetails(String mediaId, String token){
        try{
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl + "/" + mediaId + "/insights")
                    .queryParam("metric", "likes,shares,saved,comments")
                    .queryParam("period", "lifetime")
                    .queryParam("metric_type", "total_value")
                    .queryParam("access_token", token);

//            Ambil response
            ResponseEntity<?> response = restTemplate.getForEntity(builder.toUriString(), String.class);

//            Ubah response kedalam bentuk JSON
            ObjectMapper mapper = new ObjectMapper();
            JsonNode jsonNode = mapper.readTree(String.valueOf(response.getBody()));

            // Inisialisasi nilai default
            int likeCount = 0, shareCount = 0, saveCount = 0, commentCount = 0;

            // Cek apakah ada data
            if (jsonNode.has("data") && jsonNode.get("data").isArray()) {
                for (JsonNode dataNode : jsonNode.get("data")) {
                    String metricName = dataNode.get("name").asText();
                    int value = dataNode.get("values").get(0).get("value").asInt();

                    // Cek tipe metrik dan assign ke variabel yang sesuai
                    switch (metricName) {
                        case "likes":
                            likeCount = value;
                            break;
                        case "shares":
                            shareCount = value;
                            break;
                        case "saved":
                            saveCount = value;
                            break;
                        case "comments":
                            commentCount = value;
                            break;
                    }
                }
            }
//            System.out.println("likes: " + likeCount);
//            System.out.println("share: " + shareCount);
//            System.out.println("save: " + saveCount);
//            System.out.println("comment: " + commentCount);


//            sekarang mau ambil media type dll
            UriComponentsBuilder builder2 = UriComponentsBuilder.fromUriString(baseUrl + "/" + mediaId)
                    .queryParam("fields", "media_type,media_product_type,media_url,permalink,timestamp,thumbnail_url")
                    .queryParam("access_token", token);

//            Ambil response
            ResponseEntity<?> response2 = restTemplate.getForEntity(builder2.toUriString(), String.class);

//            Ubah response kedalam bentuk JSON
            ObjectMapper mapper2 = new ObjectMapper();
            JsonNode jsonNode2 = mapper2.readTree(String.valueOf(response2.getBody()));

            String mediaType = jsonNode2.get("media_type").asText();
            String mediaProductType = jsonNode2.get("media_product_type").asText();
            String mediaUrl = jsonNode2.get("media_url").asText();
            String permalink = jsonNode2.get("permalink").asText();
            String timestamp = jsonNode2.get("timestamp").asText();

            String thumbnailUrl = mediaUrl;
            if (mediaProductType == "REELS"){
                thumbnailUrl = jsonNode2.get("thumbnail_url").asText();
            }

            Integer engagement = likeCount + commentCount + shareCount + saveCount;

//            System.out.println("start");
//            System.out.println(mediaType);
//            System.out.println(mediaProductType);
//            System.out.println(mediaUrl);
//            System.out.println(permalink);
//            System.out.println(timestamp);
//            System.out.println(engagement);

            // Return DTO yang sudah diisi
            return MediaDetailDto.builder()
                    .id(mediaId)
                    .likecount(likeCount)
                    .commentcount(commentCount)
                    .sharecount(shareCount)
                    .savecount(saveCount)
                    .mediatype(mediaType)
                    .mediaproducttype(mediaProductType)
                    .mediaurl(mediaUrl)
                    .permalink(permalink)
                    .timestamp(timestamp)
                    .engagement(formatFollowers(engagement))
                    .thumbnailurl(thumbnailUrl)
                    .build();
        }
        catch (HttpClientErrorException.BadRequest e){
            try {
                // Parsing response error dari API
                ObjectMapper mapper = new ObjectMapper();
                JsonNode errorNode = mapper.readTree(e.getResponseBodyAsString());

                if (errorNode.has("error")) {
                    JsonNode errorDetails = errorNode.get("error");
                    int errorCode = errorDetails.get("code").asInt();
                    int errorSubcode = errorDetails.has("error_subcode") ? errorDetails.get("error_subcode").asInt() : 0;

                    // Handle error khusus jika media diposting sebelum akun menjadi business account
                    if (errorCode == 100 && errorSubcode == 2108006) {
                        System.out.println("⚠️ Skipping media " + mediaId + " because it was posted before the account conversion to business.");
                        return null; // Bisa juga return object kosong jika ingin tetap dikembalikan
                    }
                }
            } catch (Exception parseError) {
                System.out.println("❌ Error parsing error response: " + parseError.getMessage());
            }

            return null; // Return null jika terjadi error
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("error di fetchmediadetails");
            return null;

        }
    }

    public static String formatDate(LocalDateTime dateTimeInput) {
        // Input string
        String dateTimeString = String.valueOf(dateTimeInput);

        // Parsing string ke LocalDateTime
        LocalDateTime dateTime = LocalDateTime.parse(dateTimeString);

        // Format output
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.ENGLISH);

        // Format ke string yang diinginkan
        String formattedDate = dateTime.format(formatter);

        // Output hasil
        System.out.println(formattedDate);
        return formattedDate;
    }

    public static long getUnixTimestamp(int daysAgo) {
        return LocalDate.now().minusDays(daysAgo)
                .atStartOfDay()
                .toEpochSecond(ZoneOffset.UTC);
    }

    public static long getUnixTimestampEoD(int daysAgo) {
        return LocalDate.now().minusDays(daysAgo)
                .atTime(23, 59, 59)
                .toEpochSecond(ZoneOffset.UTC);
    }

    public GraphDto follGrowth(String igid, String token){
        try {
            long time = getUnixTimestamp(29);
            System.out.println("unix timestamp 30 hari lalu: " + time);
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl + "/" + igid + "/insights")
                    .queryParam("metric", "follower_count")
                    .queryParam("period", "day")
                    .queryParam("since", time)
                    .queryParam("access_token", token);

//            Ambil response
            ResponseEntity<?> response = restTemplate.getForEntity(builder.toUriString(), String.class);

//            Ubah response kedalam bentuk JSON
            ObjectMapper mapper = new ObjectMapper();
            JsonNode jsonNode = mapper.readTree(String.valueOf(response.getBody()));

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd MMM yyyy");

            List<String> labels = new ArrayList<>();
            List<String> data = new ArrayList<>();

            // Ambil data dari API
            JsonNode dataArray = jsonNode.get("data");
            if (dataArray != null && dataArray.isArray()) {
                for (JsonNode dataNode : dataArray) {
                    if (dataNode.has("values") && dataNode.get("values").isArray()) {


                        for (JsonNode valueNode : dataNode.get("values")) {
                            // Ambil nilai value
                            String followerCount = valueNode.get("value").asText();

                            // Ubah format tanggal
                            String endTime = valueNode.get("end_time").asText().substring(0, 10);
                            LocalDate date = LocalDate.parse(endTime);
                            String formattedDate = date.format(formatter);

                            labels.add(formattedDate);
                            data.add(followerCount);
                        }
                    }
                }
            }
            return GraphDto.builder()
                    .labels(labels)
                    .data(data)
                    .build();
        }catch (Exception e) {
            e.printStackTrace();
            System.out.println("error di follgrowth");
            return null;

        }
    }

    public GraphDto reach (String igid, String token){
        try {
            long time = getUnixTimestamp(29);
            System.out.println("unix timestamp 30 hari lalu: " + time);
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl + "/" + igid + "/insights")
                    .queryParam("metric", "reach")
                    .queryParam("period", "day")
                    .queryParam("since", time)
                    .queryParam("access_token", token);

//            Ambil response
            ResponseEntity<?> response = restTemplate.getForEntity(builder.toUriString(), String.class);

//            Ubah response kedalam bentuk JSON
            ObjectMapper mapper = new ObjectMapper();
            JsonNode jsonNode = mapper.readTree(String.valueOf(response.getBody()));

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd MMM yyyy");

            List<String> labels = new ArrayList<>();
            List<String> data = new ArrayList<>();

            // Ambil data dari API
            JsonNode dataArray = jsonNode.get("data");
            if (dataArray != null && dataArray.isArray()) {
                for (JsonNode dataNode : dataArray) {
                    if (dataNode.has("values") && dataNode.get("values").isArray()) {
                        for (JsonNode valueNode : dataNode.get("values")) {
                            // Ambil nilai value
                            String reachCount = valueNode.get("value").asText();

                            // Ubah format tanggal
                            String endTime = valueNode.get("end_time").asText().substring(0, 10);
                            LocalDate date = LocalDate.parse(endTime);
                            String formattedDate = date.format(formatter);

                            labels.add(formattedDate);
                            data.add(reachCount);
                        }
                    }
                }
            }

            return GraphDto.builder()
                    .labels(labels)
                    .data(data)
                    .build();

        }catch (Exception e) {
            e.printStackTrace();
            System.out.println("error di reach");
            return null;

        }
    }

    public HashMap<String,Object> onlineFollowers (String igid, String token){
        HashMap<String,Object> hashMap = new HashMap<>();
        try {
            long start = getUnixTimestamp(4);
            long end = getUnixTimestampEoD(4);
            System.out.println("unix timestamp 4 hari lalu: " + start + " " + end);
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl + "/" + igid + "/insights")
                    .queryParam("metric", "online_followers")
                    .queryParam("period", "lifetime")
                    .queryParam("since", start)
                    .queryParam("until", end)
                    .queryParam("access_token", token);

//            Ambil response
            ResponseEntity<?> response = restTemplate.getForEntity(builder.toUriString(), String.class);

//            Ubah response kedalam bentuk JSON
            ObjectMapper mapper = new ObjectMapper();
            JsonNode jsonNode = mapper.readTree(String.valueOf(response.getBody()));

            List<String> labels = new ArrayList<>();
            List<String> data = new ArrayList<>();
            int maxFollowers = Integer.MIN_VALUE;
            int minFollowers = Integer.MAX_VALUE;
            String highestTime = "";
            String lowestTime = "";

            // Ambil data dari API
            JsonNode dataArray = jsonNode.get("data");
            if (dataArray != null && dataArray.isArray()) {
                for (JsonNode dataNode : dataArray) {
                    if (dataNode.has("values") && dataNode.get("values").isArray()) {
                        for (JsonNode valueNode : dataNode.get("values")) {
                            JsonNode valueObject = valueNode.get("value");
                            if (valueObject != null && valueObject.isObject()) {
                                for (int i = 0; i < 24; i++) {
                                    JsonNode followerNode = valueObject.get(String.valueOf(i));
                                    int followerCount = (followerNode != null && followerNode.isInt()) ? followerNode.asInt() : 0; // Jika null, set 0
                                    int hourWIB = (i + 7) % 24; // Konversi ke WIB
                                    String hourLabel = hourWIB + ":00";

                                    labels.add(hourLabel);
                                    data.add(String.valueOf(followerCount));

                                    // Cari highest & lowest
                                    if (followerCount > maxFollowers) {
                                        maxFollowers = followerCount;
                                        highestTime = hourLabel;
                                    }
                                    if (followerCount < minFollowers) {
                                        minFollowers = followerCount;
                                        lowestTime = hourLabel;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            GraphDto graphDto = GraphDto.builder()
                    .labels(labels)
                    .data(data)
                    .build();

            hashMap.put("graph", graphDto);
            hashMap.put("highest", highestTime);
            hashMap.put("lowest", lowestTime);
            return hashMap;
        }catch (Exception e) {
            e.printStackTrace();
            System.out.println("error di onl foll");
            return null;

        }
    }

    public GraphDto cityAudience (String igid, String token){
        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl + "/" + igid + "/insights")
                    .queryParam("metric", "follower_demographics")
                    .queryParam("period", "lifetime")
                    .queryParam("metric_type", "total_value")
                    .queryParam("breakdown", "city")
                    .queryParam("access_token", token);

//            Ambil response
            ResponseEntity<?> response = restTemplate.getForEntity(builder.toUriString(), String.class);

//            Ubah response kedalam bentuk JSON
            ObjectMapper mapper = new ObjectMapper();
            JsonNode jsonNode = mapper.readTree(String.valueOf(response.getBody()));

            // Ambil data dari API
            List<String> labels = new ArrayList<>();
            List<String> data = new ArrayList<>();
            JsonNode dataArray = jsonNode.get("data").get(0).get("total_value").get("breakdowns").get(0).get("results");
            if (dataArray != null && dataArray.isArray()) {
                List<JsonNode> sortedCities = new ArrayList<>();
                for (JsonNode cityData : dataArray) {
                    sortedCities.add(cityData);
                }

                // Urutkan berdasarkan value (followers) dari terbesar ke terkecil
                sortedCities.sort((a, b) -> Integer.compare(b.get("value").asInt(), a.get("value").asInt()));

                // Ambil 10 besar
                for (int i = 0; i < Math.min(10, sortedCities.size()); i++) {
                    JsonNode cityData = sortedCities.get(i);
                    labels.add(cityData.get("dimension_values").get(0).asText());
                    data.add(String.valueOf(cityData.get("value").asInt())); // Simpan dalam bentuk String
                }
            }
            return GraphDto.builder()
                    .labels(labels)
                    .data(data)
                    .build();
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("error di cityaud");
            return null;
        }
    }

    public GraphDto ageAudience (String igid, String token){
        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl + "/" + igid + "/insights")
                    .queryParam("metric", "follower_demographics")
                    .queryParam("period", "lifetime")
                    .queryParam("metric_type", "total_value")
                    .queryParam("breakdown", "age")
                    .queryParam("access_token", token);

//            Ambil response
            ResponseEntity<?> response = restTemplate.getForEntity(builder.toUriString(), String.class);

//            Ubah response kedalam bentuk JSON
            ObjectMapper mapper = new ObjectMapper();
            JsonNode jsonNode = mapper.readTree(String.valueOf(response.getBody()));

            // Ambil data dari API
            List<String> labels = new ArrayList<>();
            List<String> data = new ArrayList<>();
            JsonNode dataArray = jsonNode.get("data").get(0).get("total_value").get("breakdowns").get(0).get("results");
            if (dataArray != null && dataArray.isArray()) {
                for (JsonNode ageData : dataArray) {
                    labels.add(ageData.get("dimension_values").get(0).asText());
                    data.add(String.valueOf(ageData.get("value").asInt()));
                }
            }
            return GraphDto.builder()
                    .labels(labels)
                    .data(data)
                    .build();
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("error di age audience");
            return null;
        }
    }

    public GraphDto genderAudience (String igid, String token){
        try {
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl + "/" + igid + "/insights")
                    .queryParam("metric", "follower_demographics")
                    .queryParam("period", "lifetime")
                    .queryParam("metric_type", "total_value")
                    .queryParam("breakdown", "gender")
                    .queryParam("access_token", token);

//            Ambil response
            ResponseEntity<?> response = restTemplate.getForEntity(builder.toUriString(), String.class);

//            Ubah response kedalam bentuk JSON
            ObjectMapper mapper = new ObjectMapper();
            JsonNode jsonNode = mapper.readTree(String.valueOf(response.getBody()));

            // Ambil data dari API
            List<String> labels = new ArrayList<>();
            List<String> data = new ArrayList<>();
            JsonNode dataArray = jsonNode.get("data").get(0).get("total_value").get("breakdowns").get(0).get("results");
            if (dataArray != null && dataArray.isArray()) {
                for (JsonNode genderData : dataArray) {
                    labels.add(genderData.get("dimension_values").get(0).asText());
                    data.add(String.valueOf(genderData.get("value").asInt()));
                }
            }
            return GraphDto.builder()
                    .labels(labels)
                    .data(data)
                    .build();
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("error di gender audience");
            return null;
        }
    }

    public InfluencerDetailResponseDto buildDetailResponse(Influencer influencer, Boolean isSaved){
        try{
            Double averageRating = influencerRepository.findAverageRatingByInfluencerId(Long.valueOf(influencer.getId()));
            Integer totalReviews = influencerRepository.findTotalReviewsByInfluencerId(Long.valueOf(influencer.getId()));

            if (averageRating == null) {
                averageRating = 0.0; // Default jika tidak ada review
            }

            if (totalReviews == null) {
                totalReviews = 0; // Default jika tidak ada review
            }

            List<Category> categories = influencer.getCategories();
            List<Map<String,Object>> categoryDto = new ArrayList<>();

            String feedsPrice = "";
            String reelsPrice = "";
            String storyPrice = "";
            List<InfluencerMediaType> mediaTypes = influencer.getInfluencerMediaTypes();
            for(InfluencerMediaType mediaType: mediaTypes){
                if(mediaType.getMediaType().getLabel().equalsIgnoreCase("feeds")){
                    feedsPrice = mediaType.getPrice().toString();
                }else if(mediaType.getMediaType().getLabel().equalsIgnoreCase("reels")){
                    reelsPrice = mediaType.getPrice().toString();
                }else if(mediaType.getMediaType().getLabel().equalsIgnoreCase("story")){
                    storyPrice = mediaType.getPrice().toString();
                }
            }

            for (Category category: categories){
                Map<String,Object> newMap = new HashMap<>();
                newMap.put("id", category.getId());
                newMap.put("label", category.getLabel());
                categoryDto.add(newMap);
            }

//        untuk get basic data dari ig
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl + "/" + influencer.getInstagramId())
                    .queryParam("fields", "followers_count,follows_count,media_count,username,biography,media,stories")
                    .queryParam("access_token", influencer.getToken());

//            Ambil response
            ResponseEntity<?> response = restTemplate.getForEntity(builder.toUriString(), String.class);

//            Ubah response kedalam bentuk JSON
            ObjectMapper mapper = new ObjectMapper();
            JsonNode jsonNode = mapper.readTree(String.valueOf(response.getBody()));

//            Ambil data saja
            String dataFollowers = jsonNode.path("followers_count").asText("");
            String dataFollowing = jsonNode.path("follows_count").asText("");
            String dataTotalPost = jsonNode.path("media_count").asText("");
            String dataUsername = jsonNode.path("username").asText("");
            String dataBio = jsonNode.path("biography").asText("");

//            System.out.println("followers: " + dataFollowers);
//            System.out.println("following: " + dataFollowing);
//            System.out.println("total post: " + dataTotalPost);
//            System.out.println("username: " + dataUsername);
//            System.out.println("dataBio: " + dataBio);

//            ini untuk story
            List<StoryDetailDto> storyList = new ArrayList<>();
            JsonNode storiesNode = jsonNode.get("stories"); // Pastikan stories ada
            if (storiesNode != null && storiesNode.has("data") && storiesNode.get("data").isArray()) {
                JsonNode storyData = storiesNode.get("data");

                for (JsonNode storyNode : storyData) {
                    String storyId = storyNode.get("id").asText();
                    System.out.println("story id: " + storyId);

                    StoryDetailDto storyDetail = fetchStoryDetails(storyId, influencer.getToken());
                    if (storyDetail != null) {
                        storyList.add(storyDetail);
                    }
                }

                // Sorting berdasarkan timestamp terbaru
                storyList.sort(Comparator.comparing(StoryDetailDto::getTimestamp).reversed());
            } else {
                System.out.println("⚠️ Tidak ada story yang tersedia.");
            }
            System.out.println("ini get story list aman");

            List<MediaDetailDto> mediaList = new ArrayList<>();
            JsonNode mediaNode = jsonNode.get("media");
            if (mediaNode != null && mediaNode.has("data") && mediaNode.get("data").isArray()) {
                JsonNode mediaData = mediaNode.get("data");

                for (JsonNode mediaItem : mediaData) {
                    String mediaId = mediaItem.get("id").asText();
                    System.out.println("media id: " + mediaId);

                    MediaDetailDto mediaDetail = fetchMediaDetails(mediaId, influencer.getToken());
                    if (mediaDetail != null) {
                        mediaList.add(mediaDetail);
                    }
                }
            } else {
                System.out.println("⚠️ Tidak ada media yang tersedia.");
            }
            System.out.println("ini get media list aman");

            List<MediaDetailDto> feeds = new ArrayList<>();
            List<MediaDetailDto> reels = new ArrayList<>();

            // Pisahkan berdasarkan media_product_type
            for (MediaDetailDto media : mediaList) {
                if ("REELS".equalsIgnoreCase(media.getMediaproducttype())) {
                    reels.add(media);
                } else if ("FEED".equalsIgnoreCase(media.getMediaproducttype())) {
                    feeds.add(media);
                }
            }

            // Sorting berdasarkan engagement descending dengan parsing ke integer
            Comparator<MediaDetailDto> comparator = (m1, m2) -> Integer.compare(
                    Integer.parseInt(m2.getEngagement()),
                    Integer.parseInt(m1.getEngagement())
            );

            // Terapkan sorting dan limit 8
            feeds = feeds.stream().sorted(comparator).limit(8).collect(Collectors.toList());
            reels = reels.stream().sorted(comparator).limit(8).collect(Collectors.toList());

            // Hitung total like, shared, saved, dan comment
            int totalLike = mediaList.stream().mapToInt(MediaDetailDto::getLikecount).sum();
            int totalShared = mediaList.stream().mapToInt(MediaDetailDto::getSharecount).sum();
            int totalSaved = mediaList.stream().mapToInt(MediaDetailDto::getSavecount).sum();
            int totalComment = mediaList.stream().mapToInt(MediaDetailDto::getCommentcount).sum();

            // Hitung rata-rata
            int avgLike = mediaList.isEmpty() ? 0 : (int) Math.round((double) totalLike / mediaList.size());
            int avgShared = mediaList.isEmpty() ? 0 : (int) Math.round((double) totalShared / mediaList.size());
            int avgSaved = mediaList.isEmpty() ? 0 : (int) Math.round((double) totalSaved / mediaList.size());
            int avgComment = mediaList.isEmpty() ? 0 : (int) Math.round((double) totalComment / mediaList.size());


            Double totalInteraction = (double) (avgLike + avgComment + avgSaved + avgShared);
            Integer dataFollowersInt = Integer.valueOf(dataFollowers);
            Double engagement = (dataFollowersInt == 0) ? 0.0 : (totalInteraction / dataFollowersInt) * 100;

//            mau hitung setiap ratingnya
            List<Review> reviews = reviewRepository.findByInfluencerId(influencer.getId());

            // Hitung total review
            int totalReview = reviews.size();

            // Buat map untuk menghitung jumlah setiap rating (1-5)
            Map<Integer, Long> ratingCountMap = reviews.stream()
                    .collect(Collectors.groupingBy(Review::getRating, Collectors.counting()));

            List<TotalRatingDto> totalRatingList = new ArrayList<>();

            // Loop untuk rating 1 sampai 5
            for (int i = 1; i <= 5; i++) {
                long count = ratingCountMap.getOrDefault(i, 0L);
                double percentage = totalReview == 0 ? 0 : (double) count / totalReview * 100;

                totalRatingList.add(TotalRatingDto.builder()
                        .rating(i)
                        .totalreview((int) count)
                        .percentage(formatRatingDetail(percentage))
                        .build());
            }

//            ini mau build review dto
            List<ReviewDto> reviewDtos = reviews.stream().map(review -> ReviewDto.builder()
                    .profilepicturename(review.getBrand().getProfilePictureName()) // Sesuaikan dengan atribut di User
                    .profilepicturebyte(review.getBrand().getProfilePictureByte())
                    .profilepicturetype(review.getBrand().getProfilePictureType())
                    .name(review.getBrand().getUser().getName()) // Sesuaikan dengan atribut di User
                    .date(formatDate(review.getDateTime())) // Format sesuai kebutuhan
                    .rating(review.getRating())
                    .review(review.getReview()) // Pastikan ada field 'comment' di Review
                    .build()).collect(Collectors.toList());


//            sekarang mau bikin graph2 an
            HashMap<String,Object> hashMap = onlineFollowers(influencer.getInstagramId(), influencer.getToken());
            GraphDto graphDto = (GraphDto) hashMap.get("graph");
            String lowestTime = (String) hashMap.get("lowest");
            String highestTime = (String) hashMap.get("highest");


            // untuk analytics
            List<ProjectDetail> projectDetails = projectDetailRepository.findByInfluencerId(influencer.getId());

            double sumPositive = projectDetails.stream()
                    .filter(pd -> pd.getSentimentPositive() != null)
                    .mapToDouble(ProjectDetail::getSentimentPositive)
                    .sum();

            double sumNegative = projectDetails.stream()
                    .filter(pd -> pd.getSentimentNegative() != null)
                    .mapToDouble(ProjectDetail::getSentimentNegative)
                    .sum();

            double sumNeutral = projectDetails.stream()
                    .filter(pd -> pd.getSentimentNeutral() != null)
                    .mapToDouble(ProjectDetail::getSentimentNeutral)
                    .sum();

            long totalSentimentCount = projectDetails.stream()
                    .filter(pd -> pd.getSentimentPositive() != null || pd.getSentimentNegative() != null || pd.getSentimentNeutral() != null)
                    .count();

            String positive = String.valueOf((int) sumPositive);
            String negative = String.valueOf((int) sumNegative);
            String neutral = String.valueOf((int) sumNeutral);

            // Persentase proyek berdasarkan status
            GraphDto analytics = GraphDto.builder()
                    .labels(List.of("Positive", "Neutral", "Negative"))
                    .data(List.of(positive, neutral, negative))
                    .build();


            // Bangun InfluencerFilterResponseDto untuk setiap influencer
            InfluencerDetailResponseDto influencerFilterResponseDto = InfluencerDetailResponseDto.builder()
                    .id(influencer.getUser().getId())
                    .influencerId(influencer.getId())
                    .name(influencer.getUser().getName())
                    .email(influencer.getUser().getEmail())
                    .location(capitalize(influencer.getUser().getLocation().getLabel()))
                    .phone(influencer.getUser().getPhone())
                    .gender(influencer.getGender().getLabel())
                    .dob(influencer.getDob().toString())
                    .feedsprice(formatPrice(feedsPrice)) // Pastikan feedsPrice sudah didefinisikan
                    .reelsprice(formatPrice(reelsPrice)) // Pastikan reelsPrice sudah didefinisikan
                    .storyprice(formatPrice(storyPrice)) // Pastikan storyPrice sudah didefinisikan
                    .category(categoryDto) // Pastikan categoryDto sudah didefinisikan
                    .usertype(influencer.getUser().getUserType())
                    .instagramid(influencer.getInstagramId())
                    .isactive(influencer.getIsActive())
                    .token(influencer.getToken())
                    .followers(formatFollowers(Integer.parseInt(dataFollowers)))
                    .rating(formatRatingDetail(averageRating)) // Pastikan averageRating sudah didefinisikan
                    .totalreview(formatFollowers(totalReviews)) // Pastikan totalReviews sudah didefinisikan
                    .profilepicture(getProfilePicture(influencer.getToken(), influencer.getInstagramId()))
                    .issaved(isSaved)
                    .postmedia(formatFollowers(Integer.parseInt(dataTotalPost)))
                    .engagement(formatRating(engagement))
                    .following(formatFollowers(Integer.parseInt(dataFollowing)))
                    .username(dataUsername)
                    .bio(dataBio)
//                    .similarinfluencer()
                    .avglike(formatFollowers(avgLike))
                    .avgcomment(formatFollowers(avgComment))
                    .avgshare(formatFollowers(avgShared))
                    .avgsaved(formatFollowers(avgSaved))
                    .totalrating(totalRatingList)
                    .media(mediaList)
                    .feeds(feeds)
                    .reels(reels)
                    .story(storyList)
                    .feedback(reviewDtos)
                    .follgrowth(follGrowth(influencer.getInstagramId(),influencer.getToken()))
                    .reach(reach(influencer.getInstagramId(),influencer.getToken()))
                    .topcitiesaud(cityAudience(influencer.getInstagramId(), influencer.getToken()))
                    .agerangeaud(ageAudience(influencer.getInstagramId(),influencer.getToken()))
                    .genderaud(genderAudience(influencer.getInstagramId(),influencer.getToken()))
                    .onlinefollaud(graphDto)
                    .highestonlinetime(highestTime)
                    .lowestonlinetime(lowestTime)
                    .similarinfluencer(findSimilarInfluencers(influencer.getId()))
                    .analytics(analytics)
                    .totalanalyticspost((int) totalSentimentCount)
                    .build();

            return influencerFilterResponseDto;
        }
        catch (Exception e){
            System.out.println(e.getMessage());
            System.out.println("error di build detail response");
            return null;
        }

    }

    public Integer getTotalRevenueByInfluencer(Integer userId) {
        System.out.println("ini masuk di get total revenue untuk uid: " + userId);
        // Dapatkan WalletHeader milik Influencer
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("userId influencer tidak ditemukan"));

        Optional<WalletHeader> walletHeader = walletHeaderRepository.findByUserId(userId);

        if (walletHeader == null){
            System.out.println("kalo masuk brarti ga wallet headernya null");
            return 0; // Jika tidak punya wallet, revenue = 0
        }

        Integer walletHeaderId = walletHeader.map(WalletHeader::getId).orElse(null);
        System.out.println("wallet header id: " + walletHeaderId);

//        if (user.getWalletHeader() == null) {
//            System.out.println("kalo masuk brarti ga wallet headernya null");
//            return 0; // Jika tidak punya wallet, revenue = 0
//        }

        System.out.println("total revenue: " + walletDetailRepository.getTotalRevenueByInfluencer(walletHeaderId));
        return walletDetailRepository.getTotalRevenueByInfluencer(walletHeaderId);
    }

    public GraphDto getRevenueGraph(Integer userId) {
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(29);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

        // Dapatkan WalletHeader milik Influencer
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("userId influencer tidak ditemukan"));

        Optional<WalletHeader> walletHeader = walletHeaderRepository.findByUserId(userId);
        Integer walletHeaderId = walletHeader.map(WalletHeader::getId).orElse(null);
        System.out.println("wallet header id: " + walletHeaderId);

        // Ambil semua transaksi dalam 30 hari terakhir dengan transactionType 4
        List<WalletDetail> transactions = walletDetailRepository.findByWalletHeaderIdAndTransactionTypeIdAndDateTimeBetween(
                walletHeaderId, 4, startDate.atStartOfDay(), today.atTime(23, 59, 59));

        // Map untuk menyimpan total revenue per tanggal
        Map<LocalDate, Integer> revenueMap = new TreeMap<>(); // TreeMap otomatis urut berdasarkan tanggal

        // Akumulasi revenue berdasarkan tanggal
        for (WalletDetail transaction : transactions) {
            LocalDate date = LocalDate.from(transaction.getDateTime());
            revenueMap.put(date, revenueMap.getOrDefault(date, 0) + transaction.getNominal());
        }

        // List untuk menyimpan label tanggal
        List<LocalDate> labels = new ArrayList<>();
        List<Integer> data = new ArrayList<>();

        // Akumulasi total revenue dari tanggal paling awal ke paling akhir
        int accumulatedRevenue = 0;
        // Loop dari startDate sampai today
        for (LocalDate date = startDate; !date.isAfter(today); date = date.plusDays(1)) {
            // Jika ada transaksi di tanggal tersebut, update revenue
            accumulatedRevenue = revenueMap.getOrDefault(date, 0);

            // Simpan tanggal dan revenue ke dalam list
            labels.add(date);
            data.add(accumulatedRevenue);
        }

        return GraphDto.builder()
                .labels(labels.stream().map(LocalDate::toString).collect(Collectors.toList())) // Konversi LocalDate ke String
                .data(data.stream().map(String::valueOf).collect(Collectors.toList())) // Konversi Integer ke String
                .build();
    }

    public InfluencerHomeDto buildDetailHomeResponse(Influencer influencer){
        try{
            Double averageRating = influencerRepository.findAverageRatingByInfluencerId(Long.valueOf(influencer.getId()));
            Integer totalReviews = influencerRepository.findTotalReviewsByInfluencerId(Long.valueOf(influencer.getId()));

            if (averageRating == null) {
                averageRating = 0.0; // Default jika tidak ada review
            }

            if (totalReviews == null) {
                totalReviews = 0; // Default jika tidak ada review
            }

            List<Category> categories = influencer.getCategories();
            List<Map<String,Object>> categoryDto = new ArrayList<>();

            for (Category category: categories){
                Map<String,Object> newMap = new HashMap<>();
                newMap.put("id", category.getId());
                newMap.put("label", category.getLabel());
                categoryDto.add(newMap);
            }

//        untuk get basic data dari ig
            UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(baseUrl + "/" + influencer.getInstagramId())
                    .queryParam("fields", "followers_count,follows_count,media_count,username,biography,media")
                    .queryParam("access_token", influencer.getToken());

//            Ambil response
            ResponseEntity<?> response = restTemplate.getForEntity(builder.toUriString(), String.class);

//            Ubah response kedalam bentuk JSON
            ObjectMapper mapper = new ObjectMapper();
            JsonNode jsonNode = mapper.readTree(String.valueOf(response.getBody()));

//            Ambil data saja
            String dataFollowers = jsonNode.path("followers_count").asText("");
            String dataFollowing = jsonNode.path("follows_count").asText("");
            String dataTotalPost = jsonNode.path("media_count").asText("");
            String dataUsername = jsonNode.path("username").asText("");
            String dataBio = jsonNode.path("biography").asText("");

//            System.out.println("followers: " + dataFollowers);
//            System.out.println("following: " + dataFollowing);
//            System.out.println("total post: " + dataTotalPost);
//            System.out.println("username: " + dataUsername);
//            System.out.println("dataBio: " + dataBio);

//            ini untuk media
            List<MediaDetailDto> mediaList = new ArrayList<>();
            JsonNode mediaNode = jsonNode.get("media");
            if (mediaNode != null && mediaNode.has("data") && mediaNode.get("data").isArray()) {
                JsonNode mediaData = mediaNode.get("data");

                for (JsonNode mediaItem : mediaData) {
                    String mediaId = mediaItem.get("id").asText();
                    System.out.println("media id: " + mediaId);

                    MediaDetailDto mediaDetail = fetchMediaDetails(mediaId, influencer.getToken());
                    if (mediaDetail != null) {
                        mediaList.add(mediaDetail);
                    }
                }
            } else {
                System.out.println("⚠️ Tidak ada media yang tersedia.");
            }

            // Hitung total like, shared, saved, dan comment
            int totalLike = mediaList.stream().mapToInt(MediaDetailDto::getLikecount).sum();
            int totalShared = mediaList.stream().mapToInt(MediaDetailDto::getSharecount).sum();
            int totalSaved = mediaList.stream().mapToInt(MediaDetailDto::getSavecount).sum();
            int totalComment = mediaList.stream().mapToInt(MediaDetailDto::getCommentcount).sum();

            // Hitung rata-rata
            int avgLike = mediaList.isEmpty() ? 0 : (int) Math.round((double) totalLike / mediaList.size());
            int avgShared = mediaList.isEmpty() ? 0 : (int) Math.round((double) totalShared / mediaList.size());
            int avgSaved = mediaList.isEmpty() ? 0 : (int) Math.round((double) totalSaved / mediaList.size());
            int avgComment = mediaList.isEmpty() ? 0 : (int) Math.round((double) totalComment / mediaList.size());

            Double totalInteraction = (double) (avgLike + avgComment + avgSaved + avgShared);
            Integer dataFollowersInt = Integer.valueOf(dataFollowers);
            Double engagement = (dataFollowersInt == 0) ? 0.0 : (totalInteraction / dataFollowersInt) * 100;

//            mau hitung data2 project
            List<ProjectHeader> projects = projectHeaderRepository.findByInfluencerId(influencer.getId());

            // Hitung jumlah proyek berdasarkan status
            long waitingProj = projects.stream().filter(p -> p.getStatus().getId() == 3).count();
            long ongoingProj = projects.stream().filter(p -> p.getStatus().getId() == 4 || p.getStatus().getId() == 5).count();
            long completedProj = projects.stream().filter(p -> p.getStatus().getId() == 6).count();
//            long totalProjects = projects.size();

            String waiting = String.valueOf(waitingProj);
            String ongoing = String.valueOf(ongoingProj);
            String completed = String.valueOf(completedProj);


            // Persentase proyek berdasarkan status
            GraphDto projectPct = GraphDto.builder()
                    .labels(List.of("Waiting", "Ongoing", "Completed"))
                    .data(List.of(waiting, ongoing, completed))
                    .build();

            // Hitung approval vs rejection
            long approvedProj = projects.stream().filter(p -> p.getStatus().getId() == 4 || p.getStatus().getId() == 5 || p.getStatus().getId() == 6).count();
            long rejectedProj = projects.stream().filter(p -> p.getStatus().getId() == 7).count();
//            long totalReviewed = approvedProj + rejectedProj;

            String approved = String.valueOf(approvedProj);
            String rejected = String.valueOf(rejectedProj);

            GraphDto approvalPct = GraphDto.builder()
                    .labels(List.of("Approved", "Rejected"))
                    .data(List.of(approved, rejected))
                    .build();

            // Bangun InfluencerFilterResponseDto untuk setiap influencer
            InfluencerHomeDto detailHomeInfluencer = InfluencerHomeDto.builder()
                    .id(influencer.getUser().getId())
                    .influencerid(influencer.getId())
                    .name(influencer.getUser().getName())
                    .email(influencer.getUser().getEmail())
                    .location(capitalize(influencer.getUser().getLocation().getLabel()))
                    .phone(influencer.getUser().getPhone())
                    .gender(influencer.getGender().getLabel())
                    .category(categoryDto) // Pastikan categoryDto sudah didefinisikan
                    .usertype(influencer.getUser().getUserType())
                    .instagramid(influencer.getInstagramId())
                    .isactive(influencer.getIsActive())
                    .token(influencer.getToken())
                    .followers(formatFollowers(Integer.parseInt(dataFollowers)))
                    .rating(formatRatingDetail(averageRating)) // Pastikan averageRating sudah didefinisikan
                    .totalreview(formatFollowers(totalReviews)) // Pastikan totalReviews sudah didefinisikan
                    .profilepicture(getProfilePicture(influencer.getToken(), influencer.getInstagramId()))
                    .postmedia(formatFollowers(Integer.parseInt(dataTotalPost)))
                    .engagement(formatRating(engagement))
                    .following(formatFollowers(Integer.parseInt(dataFollowing)))
                    .username(dataUsername)
                    .bio(dataBio)
                    .totalrevenue(formatPrice(String.valueOf(getTotalRevenueByInfluencer(influencer.getUser().getId()))))
                    .revenueacc(getRevenueGraph(influencer.getUser().getId()))
                    .waitingproj(waiting)
                    .ongoingproj(ongoing)
                    .completedproj(completed)
                    .projectpct(projectPct)
                    .approvalpct(approvalPct)
                    .build();

            return detailHomeInfluencer;
        }
        catch (Exception e){
            System.out.println(e.getMessage());
            System.out.println("error di build detail home response");
            return null;
        }
    }

//    INI UNTUK SIMILAR INFLUENCER
    public List<SimilarInfluencerDto> findSimilarInfluencers(Integer influencerId) {
        Influencer target = influencerRepository.findById(influencerId).orElse(null);
        if (target == null) return Collections.emptyList();

        List<Influencer> influencers = influencerRepository.findAll();

        // Get data followers
        Map<Integer, Integer> followersMap = new HashMap<>();

        for (Influencer influencer: influencers){
            int followersCount = getFollowersFromInstagramApi(influencer.getToken(), influencer.getInstagramId());
            followersMap.put(influencer.getId(), followersCount);
        }

        // Normalisasi umur dan harga
        double maxAge = influencers.stream().mapToDouble(i -> getAge(i.getDob())).max().orElse(1);
        double maxPrice = influencers.stream().mapToDouble(this::getMinPrice).max().orElse(1);
        double maxFollowers = followersMap.values().stream().mapToDouble(f -> f).max().orElse(1);

        double minAge = influencers.stream().mapToDouble(i -> getAge(i.getDob())).min().orElse(0);
        double minPrice = influencers.stream().mapToDouble(this::getMinPrice).min().orElse(0);
        double minFollowers = followersMap.values().stream().mapToDouble(f -> f).min().orElse(0);

//        System.out.println("Max age: " + maxAge);
//        System.out.println("Max price: " + maxPrice);
//        System.out.println("Max followers: " + maxFollowers);
//        System.out.println("Min age: " + minAge);
//        System.out.println("Min price: " + minPrice);
//        System.out.println("Min followers: " + minFollowers);

        influencers.remove(target);
        List<SimilarInfluencerDto> similarInfluencers = new ArrayList<>(); // List baru untuk menyimpan hasil

        // **Ambil followers target dari tampungan**
        int targetFollowers = followersMap.getOrDefault(target.getId(), 0);

        for (Influencer influencer : influencers) {
            System.out.println("lagi hitung similarity untuk " + influencer.getUser().getName());

            int influencerFollowers = followersMap.getOrDefault(influencer.getId(), 0);

            double euclideanSim = calculateEuclideanSimilarity(target, influencer, targetFollowers, influencerFollowers, maxAge, maxPrice, maxFollowers, minAge, minPrice, minFollowers);
            double jaccardSim = calculateJaccardSimilarity(target, influencer);
            double finalScore = (3.0 / 4.0) * euclideanSim + (1.0 / 4.0) * jaccardSim;

//            System.out.println("euclidean: " + euclideanSim);
//            System.out.println("jaccard: " + jaccardSim);
//            System.out.println("result: " + finalScore);

            SimilarInfluencerDto dto = SimilarInfluencerDto.builder()
                    .id(influencer.getId())
                    .influencerId(influencer.getId())
                    .username(influencer.getUser().getName())
                    .category(influencer.getCategories().stream()
                            .map(Category::getLabel)
                            .toList()) // Ambil kategori dalam bentuk List<String>
                    .profilepicture(getProfilePicture(influencer.getToken(), influencer.getInstagramId())) // Ambil foto profil dari User
                    .finalscore(formatRating(finalScore))
                    .build();

            similarInfluencers.add(dto); // Tambahkan ke list hasil
        }

        similarInfluencers.sort(Comparator.comparingDouble(dto -> -Double.parseDouble(String.valueOf(dto.getFinalscore()))));
        List<SimilarInfluencerDto> top3SimilarInfluencers = similarInfluencers.subList(0, Math.min(3, similarInfluencers.size()));

        return top3SimilarInfluencers;
    }

    private double calculateEuclideanSimilarity(Influencer a, Influencer b, int followersA, int followersB, double maxAge, double maxPrice, double maxFollowers, double minAge, double minPrice, double minFollowers) {
        double ageA = (getAge(a.getDob()) - minAge) / (maxAge - minAge);
        double priceA = (getMinPrice(a) - minPrice) / (maxPrice - minPrice);
        double follA = (followersA - minFollowers) / (maxFollowers - minFollowers);

        double ageB = (getAge(b.getDob()) - minAge) / (maxAge - minAge);
        double priceB = (getMinPrice(b) - minPrice) / (maxPrice - minPrice);
        double follB = (followersB - minFollowers) / (maxFollowers - minFollowers);

//        System.out.println("euclidean");
//        System.out.println("Inf A target: " + a.getUser().getName());
//        System.out.println("age: " + ageA);
//        System.out.println("min price: " + priceA);
//        System.out.println("min price real: " + getMinPrice(a));
//        System.out.println("followers: " + follA);
//        System.out.println("followers: " + followersA);
//
//        System.out.println("Inf B perbandingan: " + b.getUser().getName());
//        System.out.println("age: " + ageB);
//        System.out.println("min price: " + priceB);
//        System.out.println("min price real: " + getMinPrice(b));
//        System.out.println("followers: " + follB);
//        System.out.println("followers: " + followersB);

        double distance = Math.sqrt(
                Math.pow(ageA - ageB, 2) +
                Math.pow(priceA - priceB, 2) +
                Math.pow(follA - follB, 2)
        );
        return 1 / (1 + distance); // Normalized similarity
    }

    private double calculateJaccardSimilarity(Influencer a, Influencer b) {
        Set<Category> setA = new HashSet<>(a.getCategories());
        Set<Category> setB = new HashSet<>(b.getCategories());

        Set<Category> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);

        Set<Category> union = new HashSet<>(setA);
        union.addAll(setB);

//        System.out.println("jaccard");
//        System.out.println("intersection size: " + intersection.size());
//        System.out.println("union size: " + union.size());

        return (double) intersection.size() / union.size();
    }

    private int getAge(LocalDate dob) {
        return LocalDate.now().getYear() - dob.getYear();
    }

    private int getMinPrice(Influencer influencer) {
        return influencerMediaTypeRepository.findByInfluencer(influencer).stream()
                .mapToInt(InfluencerMediaType::getPrice)
                .min()
                .orElse(0);
    }

//    buat cek & update aktif influencer
    public void updateStatus(Integer influencerId, boolean isActive) {
        Influencer influencer = influencerRepository.findById(influencerId)
                .orElseThrow(() -> new RuntimeException("Influencer not found"));

        influencer.setIsActive(isActive);
        influencerRepository.save(influencer);
    }

    public boolean isProfileComplete(Integer influencerId) {
        Influencer influencer = influencerRepository.findById(influencerId)
                .orElseThrow(() -> new RuntimeException("Influencer not found"));

        Boolean exists = influencerMediaTypeRepository.existsByInfluencer_Id(influencerId);

        return exists;
    }

    public boolean isProjectComplete(Integer influencerId) {
        Influencer influencer = influencerRepository.findById(influencerId)
                .orElseThrow(() -> new RuntimeException("Influencer not found"));

        boolean exists = projectHeaderRepository.existsByInfluencerIdAndStatusIdIn(
                influencerId, Arrays.asList(3, 4, 5));
//        Boolean exists = influencerMediaTypeRepository.existsByInfluencer_Id(influencerId);

        return !exists;
    }

    public List<ProjectInfluencerDto> getProjectInfluencer(Integer influencerId, LocalDate date){
        System.out.println("date: " + date);
        System.out.println("inf id: " + influencerId);

        // Hitung rentang tanggal dari date hingga date + 7
        LocalDate endDate = date.plusDays(7);

        // Ambil ProjectHeader berdasarkan influencerId
//        List<ProjectHeader> projectHeaders = projectHeaderRepository.findByInfluencerId(influencerId);

        // Ambil ProjectHeader berdasarkan influencerId dan statusId antara 4, 5, atau 6
        List<ProjectHeader> projectHeaders = projectHeaderRepository.findByInfluencerIdAndStatusIdIn(influencerId, Arrays.asList(4, 5, 6));

        // Cari ProjectDetail yang memiliki deadlineDate antara date dan endDate
        List<ProjectDetail> projectDetails = projectDetailRepository.findByProjectHeaderInAndDeadlineDateBetween(projectHeaders, date, endDate);

        if (!projectDetails.isEmpty()) {
            // List untuk menyimpan hasil mapping
            List<ProjectInfluencerDto> projectInfluencerDtos = new ArrayList<>();

            // Iterasi melalui projectDetails dan map ke ProjectInfluencerDto
            for (ProjectDetail projectDetail : projectDetails) {
                // Mengambil ProjectHeader terkait
                ProjectHeader projectHeader = projectDetail.getProjectHeader();

                // Membuat ProjectInfluencerDto dan menambahkannya ke list
                ProjectInfluencerDto dto = ProjectInfluencerDto.builder()
                        .projectheaderid(projectHeader.getId())
                        .projectdetailid(projectDetail.getId())
                        .fulldate(projectDetail.getDeadlineDate())
                        .day(projectDetail.getDeadlineDate().format(DateTimeFormatter.ofPattern("EEE")))
                        .date(projectDetail.getDeadlineDate().format(DateTimeFormatter.ofPattern("d")))  // Hanya tanggalnya (misalnya 17)
                        .brandname(projectHeader.getBrand().getUser().getName())  // Ambil nama brand
                        .mediatype(projectDetail.getMediaType().getLabel())  // Ambil nama mediaType
                        .projecttitle(projectHeader.getTitle())  // Ambil judul proyek
                        .build();

                projectInfluencerDtos.add(dto);  // Tambahkan dto ke dalam list
            }

            return projectInfluencerDtos;
        } else {
            // Jika tidak ada proyek yang sesuai dengan rentang tanggal
            return null;  // Atau bisa lempar exception jika proyek tidak ditemukan
        }

    }
}
