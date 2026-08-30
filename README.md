# Contacts Cleaner

A batch-oriented contact cleanup and Google reimport pipeline built for enterprise-style maintainability and clear separation of concerns.

## Purpose

This project reads contact data from CSV files in an input directory, normalizes phone fields, merges duplicate person records, strips non-importable metadata, and emits a single Google Contacts-compatible CSV export.

## Architectural goals

- Clear separation between domain logic and infrastructure
- Single responsibility per component
- Testable business rules
- Explicit merge and normalization policies
- Simple CLI entrypoint for local execution

## Recommended project layout

```text
contacts-cleaner/
├── LICENSE
├── README.md
├── .gitignore
├── src/
│   ├── Main.java
│   ├── ContactRow.java
│   ├── CsvUtil.java
│   ├── PhoneNormalizer.java
│   ├── GoogleReimportWriter.java
│   └── main/java/com/contacts/cleaner/
│       ├── app/
│       │   └── ContactImportApp.java
│       ├── application/
│       │   ├── ImportContactsUseCase.java
│       │   ├── ContactNormalizationService.java
│       │   ├── ContactDeduplicationService.java
│       │   ├── NameFormattingService.java
│       │   ├── ContactClusterService.java
│       │   ├── ContactMergeService.java
│       │   └── MergedContact.java
│       └── infrastructure/
│           └── csv/
│               └── CsvContactRepository.java
├── input/
├── output/
└── out/
```

## Current behavior

The current implementation is intentionally minimal but production-minded:

- reads only from `input/*.csv`
- removes stale CSV output files before a fresh run
- validates phone values and canonicalizes them for Google import
- merges duplicate rows based on shared phone identity
- strips accents and clears notes/tags from the final Google CSV
- writes a single timestamped output file to `output/`

## Build and run

From the project root (PowerShell):

```powershell
javac -d out @(Get-ChildItem -Recurse -Filter '*.java' -Path 'src' | ForEach-Object { $_.FullName })
java -cp out com.contacts.cleaner.Main <rootDir> <outputDir>
```

`<rootDir>` defaults to `.` and must contain an `input/` subfolder with the source CSVs; `<outputDir>` defaults to `<rootDir>/output`.

## Design principles used

- SOLID: each component owns a single responsibility
- Pipeline design: load → normalize → merge → export
- Strategy pattern: normalization and merge rules can evolve independently
- Value objects: `PhoneNormalizer.NormalizedPhone` models a canonicalized phone identity explicitly
- Infrastructure isolation: file access (`CsvContactRepository`) is separated from business logic (`application` layer)

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.
