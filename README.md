# RafenDB

![RafenDB Logo](./docs/RafenDB.png)

Yes, you're right! **A mini Database Management System (mDBMS) from scratch.**

## Overview

| Component                   | Package                   | Group |
|-----------------------------|---------------------------|-------|
| Query Processor             | `org.rafendb.processor`   | G1    |
| Concurrency Control Manager | `org.rafendb.concurrency` | G2    |
| Query Optimizer             | `org.rafendb.optimizer`   | G3    |
| Storage Manager             | `org.rafendb.storage`     | G4    |
| Failure Recovery Manager    | `org.rafendb.recovery`    | G5    |

## Quick start

You need JDK 25 or newer. Maven is optional: `./mvnw` downloads the pinned version.

```bash
./mvnw verify                         # format, compile, test, package
scripts/run.sh server                 # start the server
scripts/run.sh client                 # connect a CLI client
scripts/run.sh driver <component>     # interactive driver for one component
```

On Windows use `mvnw.cmd` and `scripts\run.cmd` with the same arguments.

## Layout

```
src/main/java/org/rafendb/
|                       # common codes
├── common/                 # shared data types
├── api/                    # interfaces between components
├── mock/                   # dummy implementations of api/
├── engine/                 # wiring and startup
├── driver/                 # shared driver kit
|
|                       # dbms client-server
├── net/                    # network request/response framing
├── server/                 # socket listener
├── client/                 # cli for end users
|
|                       # per group component
├── processor/              # query processor
├── optimizer/              # query optimizer
├── storage/                # storage manager
├── concurrency/            # concurrency control manager
└── recovery/               # failure recovery
docs/                   # reports, decisions, contract diagrams, guides
scripts/                # launchers and CI helpers
test-scenarios/         # test scenarios for testing and driver runner
```

Each package has a `package-info.java` naming its owner and the spec requirements it covers.

## Credits and Contributing

> [!IMPORTANT]
> Semua **collaborators** wajib baca file [CONTRIBUTING.md](./CONTRIBUTING.md) supaya sepemikiran dan sepemahaman.

**Supergroup RafenDB**  
PIC: 13524099 Muhammad Akmal ([@m-akma1](https://github.com/m-akma1))

**Group 1 Haskell · Query Processor**  
PIC: 13524063 Marcel Luther Sitorus ([@Celtors](https://github.com/Celtors))

| Role | NIM      | Name                  | Handle                                              |
|------|----------|-----------------------|-----------------------------------------------------|
| QP1  | 13524099 | Muhammad Akmal        | [@m-akma1](https://github.com/m-akma1)              |
| QP2  | 13524066 | Nathanael Gunawan     | [@NathanaelGun](https://github.com/NathanaelGun)    |
| QP3  | 13524015 | Mahatma Brahmana      | [@llennmaha](https://github.com/llennmaha)          |
| QP4  | 13524042 | Suryani Mulia Utami   | [@suryaniutaami](https://github.com/suryaniutaami)  |
| QP5  | 13524063 | Marcel Luther Sitorus | [@Celtors](https://github.com/Celtors)              |

**Group 2 Golang · Concurrency Control Manager**  
PIC: 13524105 Nicholas Luis Chandra ([@staplesmaster](https://github.com/staplesmaster))

| Role | NIM      | Name                        | Handle                                             |
|------|----------|-----------------------------|----------------------------------------------------|
| CC1  | 13524039 | Rhenaldy Cahyadi Putra      | [@rhenprojects](https://github.com/rhenprojects)   |
| CC2  | 13524105 | Nicholas Luis Chandra       | [@staplesmaster](https://github.com/staplesmaster) |
| CC3  | 13524069 | Miguel Rangga Deardo Sinaga | [@miguelsinaga](https://github.com/miguelsinaga)   |
| CC4  | 13524147 | Muh. Hartawan Haidir        | [@Awan-Haidir](https://github.com/Awan-Haidir)     |
| CC5  | 13522132 | Hafizh Hananta Akbari       | ?                                                  |

**Group 3 Fortran · Query Optimizer**  
PIC: 13524048 Josh Reinhart Zidik ([@achideon](https://github.com/achideon))

| Role | NIM      | Name                       | Handle                                                |
|------|----------|----------------------------|-------------------------------------------------------|
| QO1  | 13524048 | Josh Reinhart Zidik        | [@achideon](https://github.com/achideon)              |
| QO2  | 13524111 | Reynard Anderson Wijaya    | [@RND815](https://github.com/RND815)                  |
| QO3  | 13524081 | Alya Nur Rahmah            | [@alyanrrhma](https://github.com/alyanrrhma)          |
| QO4  | 13524009 | Mikhael Benrael Tampubolon | [@MikhaelBenrael](https://github.com/MikhaelBenrael)  |
| QO5  | 13524060 | Steven Tan                 | [@StevenT-1](https://github.com/StevenT-1)            |

**Group 4 Rust · Storage Manager**  
PIC: 13524090 Nashiruddin Akram ([@Akram17t](https://github.com/Akram17t))

| Role | NIM      | Name                    | Handle                                                   |
|------|----------|-------------------------|----------------------------------------------------------|
| SM1  | 13524078 | Fahd Muhammad Zahid     | [@fahdmz](https://github.com/fahdmz)                     |
| SM2  | 13524090 | Nashiruddin Akram       | [@Akram17t](https://github.com/Akram17t)                 |
| SM3  | 13524003 | Faiq Azzam Nafidz       | [@Shui7](https://github.com/Shui7)                       |
| SM4  | 13524138 | Ahmad Rinofaros Muchtar | [@Spicycoincracker](https://github.com/Spicycoincracker) |
| SM5  | 13524036 | Edward David Rumahorbo  | [@bangedaw](https://github.com/bangedaw)                 |

**Group 5 Python · Failure Recovery Manager**  
PIC: 13524033 Ray Owen Martin ([@Tensai-033](https://github.com/Tensai-033))

| Role | NIM      | Name                         | Handle                                           |
|------|----------|------------------------------|--------------------------------------------------|
| FR1  | 13524033 | Ray Owen Martin              | [@Tensai-033](https://github.com/Tensai-033)     |
| FR2  | 13524033 | Ray Owen Martin              | [@Tensai-033](https://github.com/Tensai-033)     |
| FR3  | 13524012 | Tengku Naufal Saqib          | [@tengkuNaufal](https://github.com/tengkuNaufal) |
| FR4  | 13524072 | I Gusti Ngurah Alit Dharma Y | [@SukaBotol](https://github.com/SukaBotol)       |
| FR5  | 13524108 | Daffa Mutaqin Tetaputra      | ?                                                |

**IF3140 Database System**  
*Semester 1 2026/2027*

**Program Studi Teknik Informatika**  
**Sekolah Teknik Elektro dan Informatika**  
**Institut Teknologi Bandung**
