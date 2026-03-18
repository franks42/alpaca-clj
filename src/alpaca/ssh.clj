(ns alpaca.ssh
  "SSH Ed25519 key import — use existing ~/.ssh/id_ed25519 keys with stroopwafel.

   Converts between SSH wire format and Java Ed25519 key objects:
   - SSH public key (id_ed25519.pub) → Java PublicKey
   - SSH private key (id_ed25519) → Java PrivateKey

   No external dependencies — just byte manipulation with fixed ASN.1 headers."
  (:require [clojure.string]))

;; ---------------------------------------------------------------------------
;; SSH format parsing helpers
;; ---------------------------------------------------------------------------

(defn- read-uint32
  "Read a big-endian uint32 from a byte vector at offset."
  [bs offset]
  (bit-or (bit-shift-left (bit-and (nth bs offset) 0xff) 24)
          (bit-shift-left (bit-and (nth bs (+ offset 1)) 0xff) 16)
          (bit-shift-left (bit-and (nth bs (+ offset 2)) 0xff) 8)
          (bit-and (nth bs (+ offset 3)) 0xff)))

(defn- read-ssh-string
  "Read a length-prefixed string/bytes from a byte vector at offset.
   Returns {:value byte-array :next next-offset}."
  [bs offset]
  (let [len (read-uint32 bs offset)]
    {:value (byte-array (subvec bs (+ offset 4) (+ offset 4 len)))
     :next  (+ offset 4 len)}))

;; ---------------------------------------------------------------------------
;; Ed25519 ASN.1 headers (fixed for all Ed25519 keys)
;; ---------------------------------------------------------------------------

(def ^:private ^bytes x509-header
  "X.509 SubjectPublicKeyInfo header for Ed25519 (12 bytes).
   Full encoding: header(12) + raw-public-key(32) = 44 bytes."
  (byte-array [0x30 0x2a 0x30 0x05 0x06 0x03 0x2b 0x65 0x70 0x03 0x21 0x00]))

(def ^:private ^bytes pkcs8-header
  "PKCS#8 PrivateKeyInfo header for Ed25519 (16 bytes).
   Full encoding: header(16) + seed(32) = 48 bytes."
  (byte-array [0x30 0x2e 0x02 0x01 0x00 0x30 0x05 0x06 0x03 0x2b 0x65 0x70 0x04 0x22 0x04 0x20]))

;; ---------------------------------------------------------------------------
;; Public key import
;; ---------------------------------------------------------------------------

(defn read-ssh-public-key
  "Read an SSH Ed25519 public key file and return a Java PublicKey.

   Accepts the file content (single line):
     ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA... comment

   SSH format: [4 len][11 'ssh-ed25519'][4 len][32 raw-pk]
   Java X.509: [12 header][32 raw-pk]"
  [ssh-pub-line]
  (let [b64       (second (clojure.string/split (clojure.string/trim ssh-pub-line) #" "))
        decoded   (vec (.decode (java.util.Base64/getDecoder) b64))
        ;; Skip: 4 (len) + 11 ("ssh-ed25519") + 4 (len) = 19 bytes
        raw-pk    (byte-array (drop 19 decoded))
        x509      (byte-array (concat (seq x509-header) (seq raw-pk)))
        spec      (java.security.spec.X509EncodedKeySpec. x509)
        kf        (java.security.KeyFactory/getInstance "Ed25519")]
    (.generatePublic kf spec)))

;; ---------------------------------------------------------------------------
;; Private key import
;; ---------------------------------------------------------------------------

(defn read-ssh-private-key
  "Read an OpenSSH Ed25519 private key file and return a Java PrivateKey.

   Parses the OpenSSH private key format (unencrypted only):
     -----BEGIN OPENSSH PRIVATE KEY-----
     base64...
     -----END OPENSSH PRIVATE KEY-----

   Extracts the 32-byte Ed25519 seed and wraps in PKCS#8 encoding."
  [pem-content]
  (let [lines   (clojure.string/split-lines pem-content)
        b64     (apply str (remove #(.startsWith % "-----") lines))
        decoded (vec (.decode (java.util.Base64/getDecoder) b64))
        ;; Verify magic: "openssh-key-v1\0"
        _       (assert (= "openssh-key-v1"
                           (String. (byte-array (take 14 decoded))))
                        "Not an OpenSSH private key")
        ;; Skip: magic(15) + ciphername + kdfname + kdfoptions + num-keys(4) + pubkey-blob
        pos   (atom 15)
        skip! (fn [] (let [r (read-ssh-string decoded @pos)]
                       (reset! pos (:next r)) r))]
    (skip!)                             ;; ciphername
    (skip!)                             ;; kdfname
    (skip!)                             ;; kdfoptions
    (swap! pos + 4)                     ;; num-keys
    (skip!)                             ;; public key blob
    (let [priv-blob (vec (:value (skip!))) ;; private key blob
          ;; Inside: checkint(4) + checkint(4) + keytype-string + pubkey + privkey(64) + comment
          ppos (atom 8)]                ;; skip 2x checkint
      (let [r (read-ssh-string priv-blob @ppos)] (reset! ppos (:next r))) ;; keytype
      (let [r (read-ssh-string priv-blob @ppos)] (reset! ppos (:next r))) ;; pubkey(32)
      (swap! ppos + 4)                  ;; skip privkey length prefix
      ;; Next 32 bytes = Ed25519 seed (followed by 32 bytes pubkey copy)
      (let [seed  (byte-array (subvec priv-blob @ppos (+ @ppos 32)))
            pkcs8 (byte-array (concat (seq pkcs8-header) (seq seed)))
            spec  (java.security.spec.PKCS8EncodedKeySpec. pkcs8)
            kf    (java.security.KeyFactory/getInstance "Ed25519")]
        (.generatePrivate kf spec)))))

;; ---------------------------------------------------------------------------
;; Convenience: load keypair from ~/.ssh/
;; ---------------------------------------------------------------------------

(defn load-ssh-keypair
  "Load an Ed25519 keypair from SSH key files.

   Arguments:
     private-key-path — path to id_ed25519 (default: ~/.ssh/id_ed25519)
     public-key-path  — path to id_ed25519.pub (default: private-key-path + '.pub')

   Returns {:priv PrivateKey :pub PublicKey} or nil if files don't exist."
  ([] (load-ssh-keypair (str (System/getProperty "user.home") "/.ssh/id_ed25519")))
  ([private-key-path]
   (load-ssh-keypair private-key-path (str private-key-path ".pub")))
  ([private-key-path public-key-path]
   (let [priv-file (java.io.File. private-key-path)
         pub-file  (java.io.File. public-key-path)]
     (when (and (.exists priv-file) (.exists pub-file))
       {:priv (read-ssh-private-key (slurp priv-file))
        :pub  (read-ssh-public-key (slurp pub-file))}))))
