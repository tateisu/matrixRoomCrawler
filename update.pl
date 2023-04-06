#!/usr/bin/perl --
use strict;
use warnings;
use feature qw(say);
use Time::Piece;
use Getopt::Long;
use FindBin qw($RealBin);
use Carp qw(confess);

my $verbose = 0;
my $copyJar = 0;

my $git = "git";
my $java = "java";

GetOptions(
    "verbose|v:+"=>\$verbose,
    "copyJar:+" => \$copyJar,
    ) or die "bad options.";
$verbose and say "$0 verbose=$verbose";

my $optV = $verbose ? "-v" : "";
my $optQ = $verbose ? "" : "-q";


sub nowStr{
    my $t = localtime;
    return "$t";
}

sub chdirOrThrow($){
    chdir($_[0])
    or confess "chdir failed: $_[0] $!";
}

sub cmd($){
    my($c)=@_;
    $verbose and say $c;
    system($c) and die "execute failed. $c";
}

chdirOrThrow("$RealBin");
cmd qq(./roomSpeedBot.pl $optV start);

chdirOrThrow("$RealBin/web");
cmd qq($git pull $optQ);

chdirOrThrow("$RealBin");
# 別PCでビルドしたjarを動かす
# see /z/matrixRoomList/copyJarToSirius.sh
cmd qq($java -jar ./matrixRoomCrawler.jar $optV);
cmd qq(./roomSpeed.pl $optV);

chdirOrThrow("$RealBin/web");
cmd qq($git add public/avatar/);
cmd qq($git commit $optQ -a -m "auto update");
cmd qq($git push $optQ);
