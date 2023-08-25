-- 
-- Apply upgrade patches to survey definitions database
--

-- Version 23.07
alter table forward add column p_id integer;
alter table forward add column periodic_time time;
alter table forward add column periodic_period text;
alter table forward add column periodic_day_of_week integer;
alter table forward add column periodic_day_of_month integer;
alter table forward add column periodic_local_day_of_month integer;
alter table forward add column periodic_month integer;	
alter table forward add column periodic_local_month integer;	
alter table forward add column r_id integer;	

create table periodic (
	last_checked_time time
);
ALTER TABLE periodic OWNER TO ws;

alter table forward drop constraint forward_s_id_fkey;

create index log_org_idx on log (o_id);

-- version 23.09
CREATE SEQUENCE subevent_queue_seq START 1;
ALTER SEQUENCE subevent_queue_seq OWNER TO ws;

CREATE TABLE subevent_queue (
	id integer DEFAULT NEXTVAL('subevent_queue_seq') CONSTRAINT pk_subevent_queue PRIMARY KEY,
	ue_id integer,
	linkage_items text,    -- JSON
	status text,    -- new or failed
	reason text,	-- failure reason
	processed_time TIMESTAMP WITH TIME ZONE		-- Time of processing
	);
ALTER TABLE subevent_queue OWNER TO ws;

alter table upload_event add column processed_time timestamp with time zone;
create index idx_ue_processed_time on upload_event (processed_time);

alter table s3upload add column created_time timestamp with time zone;
