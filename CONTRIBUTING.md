# Contributing Rules

> [!IMPORTANT]
> Wajib dibaca semua orang.

Panduan umum supaya satu supergroup bisa lancar kerjanya. Tolong diikuti, dan kalau bingung atau ambigu langsung ditanyakan saja. Cukup dibawa santai tapi serius. 

⸜(｡ ˃ ᵕ ˂ )⸝

## 1. Setup

1. Install **JDK 25 or newer** (Temurin recommended) and **make it your `JAVA_HOME`**. The build refuses older JDKs with a clear message. Verify it:
   ```sh
   java --version # should shows 25.x.x.x
   ```
2. Clone and build:
   ```bash
   git clone https://github.com/m-akma1/rafendb && cd rafendb
   ./mvnw verify          # Windows: mvnw.cmd verify
   ```
   The first run downloads the pinned Maven version. You don't need Maven installed.
3. **IntelliJ IDEA**
   - Open the folder and imports as a Maven project.
   - Project Structure > SDK: 25, language level: "SDK default" (not a "Preview" level).
   - Settings > Build Tools > Maven > Maven home path: **Use Maven wrapper**.
   - Optional: install the *google-java-format* plugin and set its style to *Android Open Source Project (AOSP)*. The build formats code anyway, the plugin only makes the IDE's reformat shortcut match it.
4. **VS Code**: install the **Extension Pack for Java** and the **EditorConfig** extension, and
   point it at JDK 25.

## 2. Daily Workflow

```bash
# IMPORTANT! Update refs from your working branches too
git switch main
git pull

# Create new branch in name <component>/<topic>
git switch -c qp/client-server-setup

# ... work as usual ...

# Formats your code, compiles, runs tests
./mvnw verify

# Commit and push as usual
git commit -m "feat(sm): implement client sever message passing communication"
git push -u origin qp/client-server-setup

# Create PR from GitHub Web or via CLI
gh pr create
```

**[Wajib diikuti], aturan commit, branching, PR, dll.:**
* **Branch names** start with the component in place: 
    * `qp/`: query processor
    * `qo/`: query optimizer
    * `sm/`: storage manager
    * `ccm/`: concurrency control manager
    * `frm/`: failure recovery manager
    * `super/`: super group (anything that has common code/not in single component)
* **PR and commit titles** should follow `type(scope): summary`, e.g. `fix(qo): reject unbalanced parentheses`.
* **Keep PRs small (y'know why)**, roughly 400 changed lines or fewer. Big PRs get rubber-stamped.
- **Merge ASAP.** Branches that live for days collect conflicts from other groups. Unfinished features can merge as long as they compile and do not break existing behaviour.
- **Branch `main` is protected**: every change needs a PR, a passing `build` check, and approval from a code owner of each touched path (see [`CODEOWNERS`](./.github/CODEOWNERS)).

## 3. Rules

1. **Read anything, change your own.** All code is readable by everyone, we want you to understand how the other components work. Changes to another group's paths go through their review. **Intinya rajin-rajin baca aja guys.**
2. **Call other components only through `org.rafendb.api`.** Never construct another component's classes only `org.rafendb.engine` constructs implementations, sesuai definisi dari *independent component*.
3. **Package-private by default.** Make a class `public` only when another package needs it. Internals then stay out of other groups' autocomplete.
4. **Every `api` method's Javadoc states** preconditions, nullability, exceptions, and thread-safety. **Thread-safety** is not optional: several client sessions call every component at the same time. Hati-hati dengan race condition dan deadlock ya guys.
5. **No libraries in main code.** The JDK only, with Test code may use JUnit. The build rejects any other dependency.
6. **Cross-component imports are reported but not blocked.** CI lists imports that bypass `api` in the run summary. Reviewers decide whether they are fine.
7. **Document how each method/class/record works.** Pakai javadoc. Contoh:

```java
/**
 * An expression that evaluates to a fixed, constant value.
 *
 * @param value the constant value returned by evaluation
 */
record Literal(Value value) implements Expr {
    /**
     * {@inheritDoc}
     *
     * @return the literal {@link #value}
     */
    @Override public Value eval(Row row) {}
}

/**
 * An iterator-style query operator for tuple-at-a-time row processing.
 */
public interface Operator extends AutoCloseable {
    /**
     * Initializes the operator and its underlying resources.
     */
    void open();

    /**
     * Advances to and returns the next matching row.
     *
     * @return the next {@link Row}, or {@code null} if no more rows are available
     */
    Row next();

    /**
     * Releases any resources held by this operator.
     */
    @Override void close();
}

/**
 * An operator that performs a Block Nested Loop (BNL) Join between two operators.
 */
public final class BnlJoinOp implements Operator {
    /** Number of rows buffered in memory per outer table block. */
    private static final int BLOCK_ROWS = 256;

    private final Operator outer, inner;
    private final Expr cond;
    private Row innerRow;
    private int oi;

    /**
     * Constructs a Block Nested Loop Join operator.
     *
     * @param outer the outer (build) operator
     * @param inner the inner (probe) operator
     * @param cond  the join predicate expression to evaluate
     */
    public BnlJoinOp(Operator outer, Operator inner, Expr cond) {}

    /**
     * Reads up to {@link #BLOCK_ROWS} from the outer operator into the in-memory buffer.
     *
     * @return {@code true} if a block was loaded, or {@code false} if the outer operator is exhausted
     */
    private boolean loadOuterBlock() {}

    /** {@inheritDoc} */
    @Override public void open()  {}

    /** {@inheritDoc} */
    @Override public void close() {}

    /** {@inheritDoc} */
    @Override public Row next() {}
}
```

## 4. Contracts: `api/` and `common/`

Kontrak Antarmuka ada di `api/` dan tipe data didefinisikan di `common/`. Laporan pakai **class diagram**, di-*generate* lewat Javadoc dari dua folder itu. Normalnya:

* Until Milestone 1 they change freely through normal PRs.
* After Milestone 1 they are frozen. A change needs:
  1. the `contract-change` label,
  2. a mention of every PIC whose component calls the changed method, and their approval,
  3. update di laporan milestone terkait.
* Prefer additive changes: add the new method, mark the old one `@Deprecated`, remove it a milestone later. Tujuannya supaya tidak ada *build fail* karena *module/function not found*.

## 5. Drivers and mocks

* Every component has an interactive driver: `scripts/run driver <component>`.
* **Drivers use the real components by default.** Pakai flag `--mock=storage,recovery` untuk diisi mocks. The driver prints which components are mocked at startup. Running your driver against the real system is integration testing for free. Mock bisa dipakai untuk sementara saja dan sebagai *fallback* ketika demo.
* Untuk **mempermudah testing driver dan pengecekan beberapa skenario**, ada folder `test-scenarios/` untuk **script atau txt file per komponen**. Check `test-scenarios/<component>/` and run with `run <file>` inside the driver. They are plain files so they can change without a rebuild.
* `mock/<component>/` is written by the group that owns the interface. Mocks only return dummy values, behaviour-rich fakes that a driver needs belong to the group that needs them.

## 6. Tests and formatting

* Unit tests mirror the main package, e.g. `src/test/java/org/rafendb/storage/buffer/`.
* Class names end in `Test` so the build finds them.
* End-to-end tests live in `org.rafendb.integration` and are owned by the supergroup.
* Prefer hand-written fakes that record calls in order over mocking libraries: the important checks here are orderings, e.g. log forced before a page write, commit logged before locks are released, etc.
* `./mvnw verify` formats your code before compiling (google-java-format, AOSP style), so you never fix formatting by hand. 
* If CI fails with "Run `mvn spotless:apply`", run `./mvnw spotless:apply`, commit, and push.

## 7. Miscellaneous

For anything not yet written here, ask directly to Supergroup community. Happy coding!
