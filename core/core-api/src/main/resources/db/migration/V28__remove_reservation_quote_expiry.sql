-- Quote validity is decided by checkout revalidation, not elapsed quote age.
-- Deploy with the quote-expiry-free application; older writers/readers require expires_at.
ALTER TABLE reservation_quote
  DROP CHECK chk_reservation_quote_expiry,
  DROP COLUMN expires_at;
