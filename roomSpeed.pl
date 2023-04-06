#!/usr/bin/perl --
use strict;
use warnings;
use utf8;
use feature qw(say);
use DBI;
use JSON::XS;
use Dotenv; 
use Getopt::Long;

binmode $_,":utf8" for \*STDOUT,\*STDERR;

############################################
# command line options

my $envFile = "roomSpeed.env";
my $outFile = "roomSpeed.json";
my $verbose =0;
GetOptions(
	"envFile=s" => \$envFile,
	"outFile=s" => \$outFile,
	"verbose|v:+" => \$verbose,
) or die("usage: $0 --envFile={path} -v\n");

############################################
# loading env file

Dotenv->load($envFile);

my @errors;
for my $k (qw( DB_HOST DB_PORT DB_NAME DB_USER DB_PASS )){
	$ENV{$k} or push @errors,"missing $k in environment variable, or file '$envFile'";
}
@errors and die map{ "$_\n"} @errors;

$ENV{OUT_FILE} and $outFile = $ENV{OUT_FILE};


############################################
# connect to DB, query the data.

$verbose and say "db: host=$ENV{DB_HOST} port=$ENV{DB_PORT} name=$ENV{DB_NAME} user=$ENV{DB_USER} pass=***";
my $dbh = DBI->connect(
    "dbi:Pg:dbname=$ENV{DB_NAME};host=$ENV{DB_HOST};port=$ENV{DB_PORT}", 
    $ENV{DB_USER}, 
    $ENV{DB_PASS}
) or die $DBI::errstr;

my $data = $dbh->selectall_arrayref(
"select speed,canonical_alias from room_speed 
left join room_stats_state on room_stats_state.room_id = room_speed.room_id 
where speed>0
order by speed desc;");

my %map;
for(@$data){
    my($speed,$alias)=@$_;
    $alias and $map{$alias}=$speed;
}

$dbh->disconnect;

############################################
# save as JSON.

my $json = JSON::XS->new->utf8->pretty;
my $bytes = $json->encode(\%map);

my $tmpFile = "$outFile.tmp$$";
eval{
    open(my $fh,">:raw",$tmpFile) or die "$tmpFile $!";
    print $fh $bytes;
    close($fh) or die "$tmpFile $!";
    rename($tmpFile,$outFile) or die "$outFile $!";
};
unlink $tmpFile;
$@ and die $@;
