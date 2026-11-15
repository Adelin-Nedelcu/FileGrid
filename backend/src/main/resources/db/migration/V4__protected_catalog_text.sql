ALTER TABLE stored_file ALTER COLUMN original_name TYPE varchar(2048);
ALTER TABLE stored_file ALTER COLUMN description TYPE varchar(8000);
ALTER TABLE stored_file ADD COLUMN search_tokens text;
