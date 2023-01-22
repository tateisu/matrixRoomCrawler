#!/usr/bin/perl --
use strict;
use warnings;
use utf8;
use feature qw(say);

use Attribute::Constant;
use Cwd 'getcwd';
use Daemon::Daemonize qw( :all );
use Dotenv; 
use Getopt::Long;
use JSON::XS;
use LWP::UserAgent;
use Proc::Find qw( proc_exists );
use URI::Escape;
use IO::Handle;

binmode $_,":utf8" for \*STDOUT,\*STDERR;

############################################
# command line options

my $verbose =0;
my $envFile = "roomSpeedBot.env";
my $pidFile = "roomSpeedBot.pid";
my $outLogFile = "roomSpeedBot.out.log";
my $errLogFile = "roomSpeedBot.err.log";

my $userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/89.0.4389.72 Safari/537.36";

GetOptions(
    "verbose|v:+" => \$verbose,
    "envFile=s" => \$envFile,
    "pidFile=s" => \$pidFile,
    "outLogFile:+" => \$outLogFile,
    "errLogFile:+" => \$errLogFile,
) or die("usage: $0 (options) {start|stop|restart|status}\n");

$verbose and say "$0 verbose=$verbose";

############################################
# daemon killer

sub killDaemon($){
    my($pid)=@_;
    while( $pid ){
	say "killing pid $pid …";
	kill 'INT', $pid;
	sleep 2;
	$pid = check_pidfile( $pidFile );
    }
}

# Return the pid from $pidfile if it contains a pid AND
# the process is running (even if you don't own it), 0 otherwise
my $pid = check_pidfile( $pidFile );

my $action = lc(shift(@ARGV) // "status");
if( $action eq "stop"){
    $pid or die "daemon is not running.\n";
    killDaemon($pid);
    exit;
}elsif( $action eq "status"){
    say "status: ",($pid ? "running. pid=$pid." : "not running.");
    exit;
}elsif( $action eq "start"){
    if($pid){
	$verbose and say "daemon is already running.";
	exit;
    }
}elsif( $action eq "restart"){
    if($pid){
	killDaemon($pid);
	sleep 2;
    }
}else{
    die "unknown action $action\n";
}

############################################
# loading env file

Dotenv->load($envFile);

my @errors;
for my $k (qw( SERVER_PREFIX CLIENT_USER )){
    $ENV{$k} or push @errors,"missing $k in environment variable, or file '$envFile'";
}
@errors and die map{ "$_\n"} @errors;

$ENV{USER_AGENT} and $userAgent = $ENV{USER_AGENT};
my $serverPrefix = $ENV{SERVER_PREFIX};
my $accessToken = $ENV{CLIENT_TOKEN};
say "read CLIENT_TOKEN from $envFile" if $accessToken;

##################################################################

my $ua = LWP::UserAgent->new(
    timeout => 60,
    agent => $userAgent
);
$ua->env_proxy;

sub encodeQuery($){
    my($hash)=@_;
    return join "&",map{ uri_escape($_)."=".uri_escape($hash->{$_}) } sort keys %$hash;
}

my $methodGet : Constant("GET");

my $methodPost : Constant("POST");

my $lastJson;

sub showUrl($$){
    my($method,$url)=@_;
    return if index($url ,"/sync?")!=-1;
    $url=~ s/access_token=[^&]+/access_token=xxx/g;
    say "$method $url";
}

sub apiJson($$;$){
    my($method,$path,$params)=@_;

#    my $headers =[];

    my $url = "$serverPrefix$path";

    my $req;
    if( $method eq $methodPost){
        $req = HTTP::Request->new( $method, $url);
        $req->header(Content_Type => 'application/json');
        $req->content( encode_json($params || {}) );
    }elsif( $method eq $methodGet ){
	if( $params && 0+%$params ){
	    my $delm = index($url,"?")==-1? "?":"&";
	    $url = "$url$delm".encodeQuery($params);
	}
        $req = HTTP::Request->new( $method, $url);
    }else{
        die("apiJson: unknown method [$method]");
    }
    $req->header( Authorization => "Bearer $accessToken") if $accessToken;
    showUrl $req->method,$req->uri;

    my $res = $ua->request($req);
    $res->is_success or die "request failed. status=",$res->status_line,"\ncontent=",$res->decoded_content,"\ncode location ";
    $lastJson = $res->decoded_content;
    decode_json( $res->content);
}

##################################################################

my $curDir = getcwd;
my $root;

# アクセストークンがなければログインする
if(!$accessToken){
    # トークンがなければログインする
    my $user = $ENV{CLIENT_USER} or die "missing CLIENT_USER in env.";
    my $password = $ENV{CLIENT_PASS} or die "missing CLIENT_PASS in env.";

    $root = apiJson($methodGet,"/login");

    $root = apiJson(
        $methodPost,"/login",
        {type=>"m.login.password",user=>$user,password=>$password}
    );
    my $userId = $root->{user_id};
    my $homeServer = $root->{home_server};
    $accessToken = $root->{access_token};
    $accessToken or die "login failed. $lastJson";

    say "login succeeded. please set CLIENT_TOKEN=$accessToken";
    exit;
}

# get user id of login user
$root = apiJson($methodGet,"/account/whoami");
my $myselfId = $root->{"user_id"};
say "user_id=$myselfId";

######################################################################

my $fhLog;

sub logX{
    my($lv)=shift;
    my @lt = localtime;
    $lt[5]+=1900;$lt[4]+=1;
    print $fhLog sprintf("%d%02d%02d-%02d%02d%02d $lv ",reverse @lt[0..5]),@_,"\n";
}
sub logI{ logX "INFO",@_;}
sub logW{ logX "WARN",@_;}
sub logE{ logX "ERROR",@_;}

sub parseMessages($){
    my( $root ) = @_;
    my $now = time;
    my $inviteRooms = $root->{rooms}{invite} or return;
    while(my($roomId,$room)=each %$inviteRooms ){
	my $roomAlias;
	my $roomName ="?";
	my $sender;
	for my $event (@{ $room->{invite_state}{events} }){
	    my $type = $event->{type};
	    if( $type eq "m.room.canonical_alias"){
		$roomAlias = $event->{content}{alias};
		$sender = $event->{sender};
	    }elsif( $type eq "m.room.name"){
		$roomName = $event->{content}{name};
	    }
	}
	if(not $roomAlias){
	    logE("$roomId: missing alias.");
	    next;
	}
	if(not $sender){
	    logE("$roomId: missing sender.");
	    next;
	}
	logI("$roomId: $roomAlias invites from $sender");
	my $roomIdEscaped = uri_escape($roomId);
	$root = eval{ apiJson($methodPost,"/rooms/$roomIdEscaped/join",{})};
	if($@){
	    logE "join failed. $@";
	}else{
	    logW "join result: $lastJson";
	}
    }
}

my $signaled = 0;
sub signalHandler{ 
    my($sig) = @_;
    $signaled = 1;
    logE "signal $sig";
    delete_pidfile( $pidFile );
    exit 1;
};

daemonize(
    chdir=> $curDir,
    close=>'std',
    stderr => $errLogFile,
    run => sub{
	binmode $_,":utf8" for \*STDOUT,\*STDERR;

	if( not open($fhLog,">>:utf8",$outLogFile) ){
	    $fhLog = \*STDERR;
	    logE "$outLogFile $!";
	}
	$fhLog->autoflush(1);

	logI "daemonized! pid=$$";

	write_pidfile( $pidFile );

	$SIG{INT} = \&signalHandler;
	$SIG{TERM} = \&signalHandler;

	$0="roomSpeedBot daemonized";

	my $firstRequest = time;
	
	# periodically sync
	my $nextBatch;
	my $lastRequest =0;
	while(not $signaled){

	    # 短時間に何度もAPIを呼び出さないようにする
	    my $now = time;
	    my $remain = $lastRequest + 3 - $now;
	    if( $remain >= 1 ){
		sleep($remain);
		next;
	    }
	    $lastRequest = $now;

	    $root = eval{
		# このAPI呼び出しはtimeoutまで待機しつつ、イベントが発生したらその時点で応答を返す
		my $params = { timeout=>30000 };
		if(!$nextBatch){
		    # first sync ( filtered)
		    $params->{filter}= encode_json( {room=>{timeline=>{limit=>1}}});
		}else{
		    $params->{filter}=0;
		    $params->{since}=$nextBatch;
		}
		apiJson($methodGet,"/sync",$params);
	    };

	    my $error = $@;
	    if($error){
		warn $error unless $error =~ /500 read timeout/;
		next;
	    }

	    my $sv = $root->{next_batch};
	    if($sv){
		$nextBatch = $sv;
	    }else{
		warn "missing nextBatch $lastJson";
	    }

	    parseMessages($root);
	}
	logI "loop exit.";
    }
);
