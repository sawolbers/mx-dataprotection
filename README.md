= DataProtection =
Apache 2.0 license

== Description ==

DataProtection is a Mendix module that helps make your domain model GDPR-compliant by design.
It allows you to define data protection rules per entity and attribute, and to apply those rules consistently across environments — including safe generation of realistic test data.

The module is especially useful for:

* GDPR compliance (privacy by design & by default)
* Test and acceptance environments
* Data anonymization / pseudonymization strategies
* Secure data sharing and demos

---

== Typical usage scenario ==

Use the DataProtection module when you need to:

* Ensure personal data is handled correctly according to GDPR
* Remove or obfuscate sensitive data in non-production environments
* Generate realistic but non-identifiable test data
* Apply data protection rules in bulk using background processing or task queues

---

== Features and limitations ==

=== Features ===

* Configurable data protection rules per entity and attribute:

  * Keep – leave the original value unchanged
  * Clear – remove the value (set to empty)
  * Generate – replace with realistic, locale-aware test data
  * ... (extensible for additional rule types)

* Rule-based scope:

  * Apply rules only to a subset of objects using XPath constraints

* Locale-aware test data generation:

  * Generate realistic names, addresses, numbers, etc.
  * Supports deterministic generation using seeds (repeatable results)

* High-performance batch processing:

  * Apply rules in batches
  * Safe for large datasets
  * Compatible with Mendix task queues and parallel execution

* Java-based execution:

  * Fast and scalable
  * Designed for controlled use in background processes

=== Limitations ===

* The module focuses on data transformation, not legal interpretation of GDPR
* XPath constraints must be valid and compatible with the target entity
* Generated data is intended for testing and anonymization, not cryptographic anonymization
* Does not replace encryption, access control, or consent management

---

== Dependencies ==

* Mendix 10.24.0 or higher
* Java actions enabled
* Optional: Task Queue configuration for parallel execution

---

== Installation ==

* Download the DataProtection module from the Mendix Marketplace
* Import the module into your Mendix app
* Add the module roles to your project security
* Include the module in your deployment
* Configure your data protection rules

---

== Configuration ==

To install and activate the DataProtection module in your Mendix project, perform the following steps:

* Add the DataProtection module roles to your project security
* Add the page ''DataProtection_Configuration'' to your navigation (typically under an admin or management menu)

After completing these steps, the DataProtection configuration interface will be available to authorized users, allowing you to define and manage data protection rules for your domain model.

---

== Technical implementation notes ==

* Uses XPath consistently for:
  * Scoping
  * Counting
  * Batch retrieval
* Uses deterministic sorting to enable safe parallel execution
* Relies on Java actions for performance-critical operations
* Designed to be extensible for additional rule types or generators

---

== Best practices ==

* Always test rules on a small dataset before running them in bulk
* Use deterministic seeds for reproducible test data
* Combine DataProtection with:

  * Environment-specific security
  * Encryption where required
  * Logging and auditing for compliance
