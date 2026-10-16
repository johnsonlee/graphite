//! Product identity independent of separately reviewed expectation-policy commits.
//! Hash committed Git objects, never the overlayed or otherwise dirty worktree.
use anyhow::{ensure, Context, Result};
use sha2::{Digest, Sha256};
use std::{path::Path, process::Command};

/// Both files are supplied by the trusted controller/harness, never by either
/// product revision. Every other tracked path, mode and object ID remains bound.
pub const EXCLUDED_POLICY_PATHS: [&str; 2] = [
    "benchmark/src/transitions.rs",
    "frontend/jvm/webgraph/src/benchmark/kotlin/io/johnsonlee/graphite/webgraph/LargeCorpusValidation.kt",
];
const DOMAIN: &[u8] = b"graphite-product-identity-v1\0";

pub fn compute(source: &Path) -> Result<String> {
    let output = Command::new("git")
        .args(["ls-tree", "-rz", "--full-tree", "HEAD"])
        .current_dir(source)
        .env("GIT_NO_REPLACE_OBJECTS", "1")
        .output()
        .context("read committed product Git objects")?;
    ensure!(
        output.status.success(),
        "git ls-tree failed in {}",
        source.display()
    );
    hash_records(&output.stdout)
}

fn hash_records(bytes: &[u8]) -> Result<String> {
    ensure!(
        bytes.is_empty() || bytes.ends_with(&[0]),
        "unterminated Git tree record"
    );
    let mut records = Vec::new();
    for record in bytes
        .split(|byte| *byte == 0)
        .filter(|record| !record.is_empty())
    {
        let tab = record
            .iter()
            .position(|byte| *byte == b'\t')
            .context("Git tree record has no path")?;
        let path = &record[tab + 1..];
        ensure!(!path.is_empty(), "Git tree record has an empty path");
        if !EXCLUDED_POLICY_PATHS
            .iter()
            .any(|excluded| path == excluded.as_bytes())
        {
            records.push(record);
        }
    }
    records.sort_unstable();
    let mut digest = Sha256::new();
    digest.update(DOMAIN);
    for record in records {
        digest.update(record);
        digest.update([0]);
    }
    Ok(digest
        .finalize()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{
        fs,
        path::PathBuf,
        time::{SystemTime, UNIX_EPOCH},
    };

    struct Repository(PathBuf);
    impl Repository {
        fn new() -> Self {
            let root = std::env::temp_dir().join(format!(
                "graphite-product-{}-{}",
                std::process::id(),
                SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .unwrap()
                    .as_nanos()
            ));
            fs::create_dir_all(&root).unwrap();
            let repo = Self(root);
            repo.git(&["init", "-b", "main"]);
            repo.git(&["config", "user.name", "Product identity test"]);
            repo.git(&["config", "user.email", "product-identity@example.invalid"]);
            repo.git(&["config", "commit.gpgsign", "false"]);
            repo.git(&["config", "core.autocrlf", "false"]);
            repo
        }
        fn git(&self, args: &[&str]) -> String {
            let output = Command::new("git")
                .args(args)
                .current_dir(&self.0)
                .env("GIT_NO_REPLACE_OBJECTS", "1")
                .output()
                .unwrap();
            assert!(
                output.status.success(),
                "git {args:?}: {}",
                String::from_utf8_lossy(&output.stderr)
            );
            String::from_utf8(output.stdout).unwrap().trim().to_owned()
        }
        fn write(&self, path: &str, contents: &str) {
            let file = self.0.join(path);
            fs::create_dir_all(file.parent().unwrap()).unwrap();
            fs::write(file, contents).unwrap();
        }
        fn commit(&self, message: &str) {
            self.git(&["add", "."]);
            self.git(&["commit", "-m", message]);
        }
        fn identity(&self) -> String {
            compute(&self.0).unwrap()
        }
    }
    impl Drop for Repository {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn policy_only_commits_and_feature_merge_or_rebase_preserve_both_product_identities() {
        let repo = Repository::new();
        repo.write("backend/product.rs", "original product");
        for path in EXCLUDED_POLICY_PATHS {
            repo.write(path, "initial policy");
        }
        repo.commit("initial");
        let base = repo.identity();
        let original_base_tree = repo.git(&["rev-parse", "HEAD^{tree}"]);
        repo.git(&["checkout", "-b", "feature"]);
        repo.write("backend/product.rs", "feature product");
        repo.commit("feature");
        let feature = repo.identity();
        assert_ne!(base, feature);
        let feature_commit = repo.git(&["rev-parse", "HEAD"]);
        let feature_tree = repo.git(&["rev-parse", "HEAD^{tree}"]);
        repo.git(&["checkout", "main"]);
        for path in EXCLUDED_POLICY_PATHS {
            repo.write(path, "reviewed feature expectation");
        }
        repo.commit("reviewed policy only");
        assert_ne!(original_base_tree, repo.git(&["rev-parse", "HEAD^{tree}"]));
        assert_eq!(repo.identity(), base);
        repo.git(&["checkout", "feature"]);
        repo.git(&["merge", "--no-ff", "main", "-m", "update required branch"]);
        assert_eq!(repo.identity(), feature);
        assert_ne!(feature_tree, repo.git(&["rev-parse", "HEAD^{tree}"]));
        repo.git(&["checkout", "-b", "rebased", &feature_commit]);
        repo.git(&["rebase", "main"]);
        assert_eq!(repo.identity(), feature);
        assert_ne!(feature_commit, repo.git(&["rev-parse", "HEAD"]));
        repo.git(&["checkout", "main"]);
        assert_eq!(repo.identity(), base);
    }

    #[test]
    fn every_nonpolicy_product_build_lock_fixture_catalog_path_and_mode_is_bound() {
        let repo = Repository::new();
        repo.write("backend/product.rs", "original");
        repo.commit("initial");
        let mut previous = repo.identity();
        for path in [
            "backend/product.rs",
            "frontend/jvm/webgraph/build.gradle.kts",
            "Cargo.toml",
            "Cargo.lock",
            "fixtures/real.jar",
            "benchmark/src/plan.rs",
            "benchmark/native_queries.json",
            ".github/workflows/benchmark.yml",
            "benchmark/src/transitions.rs.backup",
            "other/benchmark/src/transitions.rs",
        ] {
            repo.write(path, "changed tracked contents");
            repo.commit(path);
            let changed = repo.identity();
            assert_ne!(previous, changed, "unbound path {path}");
            previous = changed;
        }
        repo.git(&["mv", "backend/product.rs", "backend/renamed.rs"]);
        repo.commit("rename with unchanged contents");
        assert_ne!(previous, repo.identity());
        previous = repo.identity();
        repo.git(&["update-index", "--chmod=+x", "backend/renamed.rs"]);
        repo.git(&["commit", "-m", "mode only"]);
        assert_ne!(previous, repo.identity());
        previous = repo.identity();
        repo.write(
            "backend/renamed.rs",
            "dirty worktree is not the source object",
        );
        repo.write("untracked", "not part of revision");
        assert_eq!(previous, repo.identity());
    }

    #[test]
    fn raw_records_are_sorted_with_domain_nul_and_exact_path_exclusions() {
        let first = b"100644 blob aaa\tfile with\tand\nbytes\0";
        let second = b"100755 blob bbb\tother\0";
        let mut forward = first.to_vec();
        forward.extend(second);
        let mut reverse = second.to_vec();
        reverse.extend(first);
        assert_eq!(
            hash_records(&forward).unwrap(),
            hash_records(&reverse).unwrap()
        );
        let mut expected = Sha256::new();
        expected.update(DOMAIN);
        expected.update(&forward);
        assert_eq!(
            hash_records(&forward).unwrap(),
            expected
                .finalize()
                .iter()
                .map(|byte| format!("{byte:02x}"))
                .collect::<String>()
        );
        for path in EXCLUDED_POLICY_PATHS {
            forward.extend(format!("100644 blob ccc\t{path}\0").as_bytes());
        }
        assert_eq!(
            hash_records(&forward).unwrap(),
            hash_records(&reverse).unwrap()
        );
        assert!(hash_records(b"unterminated").is_err());
        assert!(hash_records(b"no-tab\0").is_err());
    }
}
