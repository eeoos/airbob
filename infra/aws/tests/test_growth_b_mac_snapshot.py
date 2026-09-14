"""Pure Mac snapshot admission. Historical public child bytes are actual;
new snapshot API metadata, restore events, targets and SQL responses are unit
fixtures only. No future completion or production manifest is produced here.
"""
import ast
import base64
import copy
import datetime as dt
import io
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import zlib

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
import growth_b_mac_snapshot as m

# Exact 11 original public JSON children, including raw formatting and refs.
# Embedding keeps the offline/CI test independent from the local artifact tree.
HISTORICAL_CHILDREN = (
    'c-rlKS$A8<vF`i*6*_u72HEqV`*JCYwrFdLDBFqSEUX?W*dRb-kSt~8zu&Jm24V&%Qa;vMPP`Ht*x0*vbq!w)-'
    'SzK>soxupugc(g=yhrtjvhYx_rpPW|NW@ChmRiq`{&++2R}a?rnluP{7?qNPQUl_!$%L)SCcPt7=>XOEqwFy!&rLh?Nu23+M0gbd'
    'Z&JVxcr0jJh}F=+mE5!x*YW1kKVRoE3z7MP?-'
    'zOc`0%JyWhs4+o_!tM)T)fJU@B%to`ie>B+P9#p&6Llh@}jFXlhE`Y`;hOBXh;K%cx@B+?zJBhIH+)3>$z;eGh9@FK#|SO1ybZq&'
    'IdOLrUg!u9ZNe{?c@Stqv?OqdpPEymw4_Ne*iznRDXE<7cqQRiJ}^x<?o>eqVqd+Lsdop)vN25RRGQvYp#FnV3C@xPtk<r|RovEQ'
    '5C;AGed#}}nP?k@adI0_%=vY&9-tA4kWK1?pYE`wILGo-sbI6rxD@*ws5gWTzbQF%bO{J#%|{qZ1`7A<8NJ?P}p8-'
    'er(gE9)8Ucm}3GAMwaiq{)1aVd|lt`|ON7~Sbk$qk)B?8oWiA7QzUE<er*Z%BU|gf>&DT9FLLod{8BBSZ1UsN}eI#k!g`t3o8FjH'
    'seSuY<^mcsRN5f98z2>Rb*QaT;!=aDGXPJ{unWG9=m9LSfEAlwymjDMaZ@lu<_+vN6mHsROf`N1K(5sYJ^abI~Q0L(Iu~H{<6bg%'
    'xX42G@g5Z?u)fSZaSzS__x%rc#*7tdzETtAx{q1y_ZtO3D&zO47Q-'
    'b&{M1&>BjWC0HSB71iVK!q=%Bl#sWxkiKmVQ|X04r@x1TrX+8x6InYWEMp=mS8K4mGBC)2^30f$T*T^Q4LLBALXag3rSYNOK-'
    ';*tqPZ*7O}=kkcSCPCZN=IEDKn9CV||FiMW0Q@e;H@SyA%?$*<~J5wIx9eO95At$y!4M-B#Mp-jQad{WKoT#E>12%1dK)EmjKeKt'
    'q**_{oh9Nz;0=!iDl)7iS~a%quX`D{IyXojv$(t2THexwETVOD20a&06YY8L0s;pjm>AIru1ah89CrwBtd^3~tNlYUC!T=&NNig7'
    'iRcVwGkr$J^~JZN9w?fmJGHgRoF$Z@h<AyUG*u)@Un3G|9pinUsA3x!R@@Q~;e7f5RSexq+!A4AR@}Tx`C+4T;PdE|N*bYGX`>X-'
    'PG~1~OHIfa0i}A+)|~XuQl$R4Wq?#-<39sy7+PL4l{<N+B!`Ja===;npWvp{-6LXy?6gNjPXy);>FDJ=Pt(4Y^oVN?}-JSW_-'
    '?jM3W?KquR@(4)}pUv4Gyw{aPKXicx$O&s?odj-p9qZG!%ga-'
    '$WaIiBzSm;aiMmbpWSaO6N%0^02jo=EDLRQy_dpjCkpY{Pf!sT{hq>EZ<dev?UUGbG0u*fkf0=NZZlsQ*{aY$of+-'
    'Ft{gYgMc<jifvx0RC(C=M#KPT?pFF3V_#2P|PY9+cdA-yi(i?ZcK&f3ZhjqIND8fhB`?t8l~0r%<Fd72MOd*qo}Ao(TcF!-'
    '9;4XV9K>%}}Wg3O(xgyW7dU9+c}ab=A$4M<8<-oUqDR1rU*x$*>`CGg)AzVZIe8kw)>1CG|cA!<3Gda}O_Bq4cUkeZpn}FB-'
    '(OolLr>HGx%o7zlO=CmG9%WuGM}jFn7Qp;Ib6MYtZPq|nhS4&MlxqVpmI&l4olZLs3gmAnFq+q>M&MSD!PEmebYGAjWIOG$eW00S'
    'O)lF7R$jn)z-vq~Ergh4as6qFh^n}xJNSwc$v@h;+K7wqwW0e(4xWkNCsV^^)Ot`-'
    '$svYb1YqbW6q09(mGDX#Tk9{wts@s#UU!k+anJH5TswYqfKt(>386>NfWNx(We0-M3SRDjtkA$ivD2v%}<?+j~hs?TI`Bn-JD%F8'
    ';z5h2!(Q+?e|FkLkV6^E%c^d%C^5w<X8n1nrw$r>njDjEx}0Df(ZV5~?0nK5ZtBwMrqZZA5fby7Q5FnZf*Y`(q?GmFy_^vPO;h38'
    '-a=Db4e0<=9FOBP0|YBGxfSkeg&l&J|-'
    '7U4bM<T`~E!kqU;<#K@FXDgv}RjV_*Y#)u#>}G{s0BI3_CkMy|^czeF5L%!HRWd<HXgaWO@HInFT`&k3Hh_sV9t@zePvGTfRiEEz'
    '@m^G!r@v_}sQw<?9yCKFRuSv?5O{`Maw|OCW%7mBEE9~Wty)A$paW3eX;tBYfRdu!6z7bl;pt%jb2N>4ZzC20h|IzSm;_`@;3Fyq'
    'Z|Q+81B`nXT1mtZwnSDGJf~v{iy^s000ddz!17VKLJS_d#Pc6ON8Us9^zw$^t#|Vu?9sOhKq4ZHDa!)dBw<xK;2HuEaMHnZde{Mr'
    'XiY?(fJZ&_lNSg-oUN^P6Q;W+$krR-'
    '>F>(PK9jqAdFwC>%#4ODGqo_SpjGgxfX{#>B^dIkj`JKV{Rtxhg9|XqxC<%ZWjV~?O&GV7U%0;hHViHn)rgTNu27+1r3Szdi6cJ9'
    '(MMh3asV!(_L?a`4(*Hb)^TQGU^M`-(`lY6O5r-hPPa4al;!M2=jv(<?dX=*vk-SQ7?;%m?$r(USi58b<1ePUBKo?9?r`<{*CmcS'
    '-8>belGkq2#4ArjFL%pe`&GY&`fE7N>8zLE^zlDSS1vy{ydR$ZUecI|UcYRS?+whqNTFLUO1Grd=gu>F%_XzgWpZI_n%#@7QR%g='
    'I^Aw(E;V3oV=U>p8zSJJAHLiAnqYScqf-'
    '*ZCQmp2;<s^sR8IT7(IBMJgp?nIdoU<B>h|D$XY{r|9zAHTeNcYC?hHOm6Inm^@Xy1qaQ|8cL=UAx<A+fhK76F$YQK7T9Y${-K6-'
    'fk{jdy%$G?Q~w%_lixBbrXa5T;zj!*l!T)-'
    ';j<7sMZo)kNtvErDLe9UukT&8b5z4rL1jJk7<9XA4TJk5(j&~EO~ngm|6`?Q!o9)RIkgX38)w{<m5<~DkYSDI)iNUsVu8POGsTn2'
    '9$O9-'
    'DWRD@7BgH{9tWN?tdC$1xt!5a8<gQ%z|8LfZ#pZ|RIaAE_V<nG2?F*%brq%^~qz3zt<VTt2mr&or<*L@N|N@A``C#5*AuDb<aoQx'
    'LpqQ1q|8!jF(`AFL%W|$V@zndiKos-MCe-%1Ck{npNr(yWE41XvCxR!O|ATf<>ziHsolEj8?x1UI2FGhU|<-'
    'X}Wrtg6;ZqQC|I0{(v({30Jr@$c|jpWhr3cp@R+BVtiA9g0J2YGnjq+~(nU@4*64Y4H^LPU??fT`BXuU?l)1Tte&j;$x+#Zz%|_T'
    'BTpfB)hi^1H9U{l`E4_U!44pUgiN6Uj{xVDU*gE`D7G2w}e;cc!<E3P8U^)e5z;7KNd$zz{z&fiTJi;i_6mA=h!Yai>&J*lfDM!D'
    '$xyq>d(U{qI|_Z1M&#!d1EFx41zC-Qj8H`t58%-'
    'aNZ#pPXH^p?vLcPM^0go}Ngjmfw2zMvt3B?*EEc`nA)WKI<x^7DTQ!F&Qm*uvX691^jbUujxPsMtGY>FaG(j>Bso-'
    '$#v&^%A=W9KK}6LZD;s;-0MxqT9lBv`Cj(rt97mw$kW*Qrm{tr{^oNs`IA@q!mR#w_<lzhupvUq-E%V8R}-'
    'ALhJtT+YlQlYyiK_H82mgQ4$?7MblmV=IPMQFkIV0YD4eqP_?zbczFy2);>zs-'
    '8{7ty!q)^KD`bknCZ+%<BO(Q`(u(Arh}PC55d1PAP=N2MvvZWmHn*wl%$da*!lo}v6930)QH#b0Bp>9-'
    '*J|2)_C@0z7b6W(D%KQQ<3dT&ctkL%as{GNW>$nCl~K*Yo8Yxj4$(V{A_|Jh_yW*?IX|a3a5Mqm8;BZ(!FJ@s9w~XmtdoMRARqj0'
    'G%Er>d)Ahs{rKeKto`QX@w2n`>C5k5yn)qz@udCa$+PzJlQ*YN&z{VG+eA3a#**T^tE>LTm=@Z;#2bWDMX73>NH7{2W<rRN5m##&'
    'f=VgJT(~znc`TXmqAFozv5Hj#ELN6;18dh1VkOX>-;{@&Q`Lsc>#b*GR;bHZixgZ$$Y~+0o-'
    '>WO7?Hi?j3@3PW0Kiin3G1cVzTzpWC)wi2pD7CNFAqwpEi5wgM$JN+(Tb9)g-'
    '}9k}`o!HON|h^e&d1q(j`yJh)V(<uwb+0XXZFO)$hdn@R!x2Ks>fV6Jyl*Cf_<(}pEX8EY7_7r|nArKVJ+C2yc5SB!a;;3T|)8O#'
    'prY!q|F7m-b{I&Y*+Y4BU>J;e3iL4Cozb`E<OC>%?%2#B5WNtfiRE>a3<1;~>;xsoN!3M<<CB30JG#!xNHIge6Xy^%;^tYzMr-Bc'
    'p3?3DLy_};6qZ=I=Ep(d6rj9s8)z7s`M<3*HUo6A^gC=s+LXhVjOD&1TrAs7!n7PgU0Er9a*8j(2<%5b=6ZSm?_Lh6)H-'
    'uMtTcgg#pVgP#}lSRXuaSJ_#+b=R<#i1cyf{j>zbqlR+Sk`NkbKXr;jSO<HYlZ_*p~NJa$g*0hV^y7u@r=qvAXC~5!>G8+QdkcuR'
    'oc7FmPDBy^t9pM#@D;4(#(^92V0Wnuq3Iv=sX-kN{~32g`lpJoPES9!Z1K>1q<H6b>T&*5VJ|Z)`=Ql@3XYd)dbUzC+8?KcRcHoc'
    '`Q+yQZ@9iM9?W<xVYEM7#WO!8G+>K!cC2vZJA(ea4vMZA4s}OZ(Q2sS@sB1fU`3W(&S*~nKCgNj%Rxv6Dx4pMpMbM^5~Q%E1WDDj'
    'O4(<l(qAljhM9#Z?OiPKwQbN5V`ygn9(LG_tKZ6O4$~N#p2pPtSnDiNY5cr(6&-'
    '?)H*0BxKvTW(ghv?Njx=r7#%j!x5l<f16g|lD-N^%(m}>y;=M?PaX3*}F{oUPNofOH<(T&HRux~uYUyMR1KI>Uij-'
    'CrX|rs(^~&E>Tnd&qkb-+FDxE;9MA3mt+ZM<rgveb$d|)G}tdvyc(E!yz-Zj&zI4*S-elw3GW3Xa;Gd=A?WFBr-'
    'D+?aMTWcMuH0&8X4E`yh;<>SM;keU@7f4(pXXRnrDKl<V<y-'
    'u>gJRk(WF53}zFNy6FooL;gH;`eS_)PI<EA*TM1h4U#(`jO;F9niJS1FSs2OVNZ^N#BO5^L$OCQ?T^Y*>%l)+lVIw*41cu!WLWeE'
    '~R0uQfaz%3ag73N%I6ynM1Drkg30mkJf-NLf>$D`Q)eUH&n#bB)<Se!$`Y&AAJZy`kRfR!4OAZozXz*^eEl19!Xpc-'
    'hh3?@S!rhC`n!-itl3Mo`k<J4cGDQ*MGDj_C_3n0CBaLK{~&BMT&!Ud0sx;?1b1FpFu-ZPs?ao&5Y?G}RC{p&I~07T>6Vaf$`T+x'
    '$?=LT}0i!k0tC0#C>qQS_x0K}$Bp$-'
    'VpnMiP&Aa=8uGY4@pY%guF^}~L5yq`?i<C?V;!terLWQBszE8~kpOoX_^LExbYE;ijd_=0OMtc0aEfEStHYBAfRLFf&sVRj!`sWR'
    '%RWED%7t>A$N{2kz%0sg$gpBW~>;*Xkm3erggoY`=1sZ5sjt++?!u)(_n))cx7yhI&WxDV8=8fwuAK?6Z6V>oa<gUMwXXj;jTGz('
    'Z{mWv0dOU`Yf3jYzt?Vzd+2b6w=Zem$~8?R@&RuvRpIyoT)!TOqH!P9xIupUYSnzDcxR@)4{wD9lHQ;?nu_3JQ*rZ{j-'
    '7wJ35zyhoDT6|W_CW+Vw5D%V66blX20LZ++>l76cAw>ZfBssUjY7mL&f?~6c238mb#Orra*A#~KxNTT>MxB(FYL_tS9u66v(*z<1'
    'Xc5Q@TB#{Ugueopa)o)re{wW|g+p0J;l&;|L~V$$5|l{cz{-#wowU%fCCu{>VOI@enG`ZYbA-Gyz~aJV0iOxDiOrI)n9)wD&GZ}+'
    'IA*Jb2^>~I3Z^12)XgUlgu|dI(gW`+tN~&U@{v9xa0`aI?SRLutDA!z4qPp`T5qEqjk;yO@wcfUVl~Xc#{^SLwI<bCbMn$5j&?~S'
    'JPXw5Wx>fP3$+dbud*D2Om?$dlz360{C1iS1QSdrz^Yg?<i_NL1w&xSm9VfAAz1JeVJ+la6`%)+PZ4i`aEosYOPg2GSgj0Sb4>3$'
    '!?)D+zlU265n%9~RcWKf0y$-i3B@A{f-'
    'L}fj#^8r7m}LzfDp0Fz^}Y!a@$*Mmu_s=Hj3J}NIG#rAsKZS3Z>wB1oV)5%0vK415W@Z2jdcaqh^u8R|0QPYNx=>E6C<%S1t{;*e'
    '#|Mca?+@9N7CV3C~LM!{gNLk8`VOxSVHNqYC^uydSoxs?urlh^;{x4L-'
    '~+p{*mwIWCk}TJu{jT{A7mP$>qsZ`>UnPyTPMUhE>GIoL6%h*1J?DTbmbd!|JZ0GE~{B#?yT3}86(z`ubpReh61C~Bcz{9|WCmEV'
    'mu<1{Eug(q}}uP7lq+>jbA-'
    'EciD`J$wLZ_<pxy*%<>PrvWSrqGp7MrYUkNoO^;AkJAO?ape_N{yO!EKuqC%`*O2x`LSX>b?{5e~Q(gr-dh<EkT7l3d8+mtGmO~L'
    'R_T2uX57MPy54BFI<(?)!e1K+R86gb#FeM^+~KLZFGqK;8u$Oj?@fb&T8@^g|Xs_Kp4+q#jOfaDMW!%Czx!@08BHmjFFAf=KlNMu'
    '=BBO)Yov`c|8G_8+&(P#5?uFlgH=Nc83*XesX=?{cwJDRdNSQxY8psx#Z@zds-!yFFw2>VjbOF_1~2@;d1!(pno-Mkys&o_VGfwe'
    'b9S^Wqkz0@+e%ldRW)DB@9O`@yJu1L%;$#<{stvXm4`{JplxKS3XQi;ImKZ^T)H+&M7-)-'
    '~DS1do%>TjB`M{1k4g3wT;eczzJZjYyvetB0kdEhQwK6+0gb8giAhAzZ{}-'
    '1bGzh0SM{Erh<j%Y_Pd}3OyFO_Xd5)_EI!iM3qUBqb?i>Na?`23(86FX@gIJuLdMTFpldA@L2?z6rYreXhq)?zZM#XR>%;Z&339*'
    'EbFo?U75~Nm)dydH#(#N<)*h`UJ#u%Im}dMe!oeC-'
    '5;#93B~i?C8g+3J9#jx^Bn=p965F5*s&Cg8%D1>vpyaC*<vi}D3oP@GE}gyi<e$spA$HLJu`Sybh#jhNBP&mQAvZNWm!E6SK(v72'
    'SGjpFK6a%P_Fy`Z(>v)DJ3*;CpXCy$O`RqOkR1J63k4}o^q&0csKxQIlw%SGbbsa^ln7ZwoE2Oq2zi0;S>1VZl_n4)=uGx%H4Fdk'
    'E!?Z6!@?+EsZJ*x57l$%4-'
    '!WVpm?ZkqD$q&LtXOq(B@i7oI%1$x`M3@kflov+SwLh96jbR0C5UVB1v(;2oJS6?a3}&TNU5>VqW*ogA}^8*`~e^Q=d;)D8WXvo7'
    'n${r+e;8iebcuJMI?O#9bnEh<aOVl!Yr!a0O5aIK&~19FF|1?>gWPB1B%u?V+JQYkwJL(Y88l4TZH@!Ov=t$@#@>>K^@dt<(NeDW'
    'ch@%hJh;=B4yef;g!@7*>(`r)_B^&7pORR3?)bJzU5H+()V3gKTmgOWzAe$z5IzohBUEW=qj{6;VqH*Q1JX(x35*V5oLYvx}~h<Q'
    '9IxszRg9@1Jn>GYyW7jIeXS)6ZDc+$C?CR1ij4EL!Oon9S;W%XUHYCJi6eD>4%_s`qs&riNNyQ6Y9&pU>ql#iY9)lAK6^2soSe9R'
    'cYI~A)dDfvLjGQ$*$k#=(+9u~adzuH*XVh`Q3Ce*Z2O+9;da`EQ;^y2K~_36|0+23EieEsI^^*vILh+sJCr0G`4GHE9r%-'
    '$tM9(l$Ca>$5JEpuK&)vIBMY6XHp9T7XLB%|igELJI}TC&i|R|)gF<6S<}E6gaD$umv_CntoLTvn(sydqpA)5d{%6D^YhQiT|}IF'
    '(g~sxuR~&Pp!lPriA5^7YA!lY6B3au90hg&DQNc%VlG=xE`ZAmcgah}bxCRaq?@PAU{*YyuXQuv$DZSOOOyL$bsLXGhe2ck=aj_e'
    'pEh5<jO@Chd&kTx(zpLt6)mDje)HjDuvu5*I0}swsm2Z=wv<zqR8+2E5}Er7vDSIr|(!-'
    'GfP7lVwGDCdGh<;IP0^NP<*S2U!4<C=}y1b0>ApoMrGe6ai1cARVD3%a^lPuU@}=e)i_++4mRsNH%=>Rl$Yh;f&@IZSg8tM_p5J%'
    '!v+bfYMc<Jt~pMfJhR844XnJLPR*3Ho8<L64OyFx8A%ydvbp9IoLfw5HeinH_O_B)0%d$!ZsKLU%dE4?K)txpk5A}Gr8Jii{!LNm'
    '=w?jLv@F|f%bbQc}A&*>q*6Dm}y5<20Bq9SyvSh3+E9I3UCI29c_pM_y=+jH8^Dj%!q*itw%x>xPDW56Xd!T&|J+uH!yDP53VM0-'
    'c1o_;iQqyu-_#p-J;COaxeN>?-Iywf1~@xU~m<{PvW>HEWK&Z#|u7O+_5vmIlG$V*IEO`Vk|R5^~)^=A|&`h2(YwC05L-'
    'IsP<`%L-'
    'Y*{mYH)QAjnDpl+NXnlZ%DJgj=u8JY=AQC}uJBck@t#G6&N)8@6Z*OAEpZGYr1t^9qRcb`d&FZu9MMG5YHvwwZm|AC8(L(Ne)hSY'
    'Ko1o_1uX=(VSmK$6@UEdi{(fyII3!0=EBLDm>d%`S+jY%205xiiQsy@Z3_{m06nx0kQ`)Arfzf&Nh%U5<?rh5a%Lt2zckmi25AkA'
    '1?{icj5Fap#Q_c#+e7uXZlSgSD7nGpg7HI^&~Ir<XzG%$?;WarizUIS;P~kg9aFyu!g)2Vr(pilEw6UdRVF^0!pB4nj0hQqF{|Le'
    'cm~MMP5~)3)`PVzT%@%NgLJmy(nBB?Fam0;0J|2nGa%uOPD<<#l_fE;!c#P`;RS&8{`mt~K%kz1qTUg|h+5<4Su*SSlxD5iuEf9>'
    'W=MtP)-)j@U0GO-'
    '(r>8KG4U>y5n2O(BwlX(l!&2LM;9nqZrgr5Xfb>VyDO=oobd8WM;ra|*fejBB|vY4Pr_3sm^S#s=Y8>h$8|`C0q*lZ*C~mp{I^IR'
    'EMFNs}~%e+1-'
    'v`TBYL<k|T*FBVd#&2QH?&QR<<t?5Cn0xy6%rbYdnE#Y0oU$wRX<Q$!ALI+<g%Wfw$$82eHvk`CQ+#D?>;&cgYMU_cICo(D8_{?B'
    '0J=N}6gteiLtb`!S=B9>A4I0`9w=C(+D{R;)b6@YkH@<@k)E*3f3StXOz+6zntfH+6agP@)F%LWr0b$%C0(SuFk^>xYz+5n9`5i{'
    '}T^J0P3xLu`2QZwAgtBUnKt5306{OQRpGDOL!F@G=b+ST=fM20kyp}2oID3;E?{7<&?#zY@X8w%n@Mfby=i?+Bj+MVG^Ay|m<ab3'
    'p?id|^0?-Tw+UUSP7$aD-'
    'R7Ic@Ns8yh>kLpQR0)U$n}c$ahI%&1@41KEyu%TGxzoE`>zE(+=35UJ9ok~<YyTEOaOKsYzi_NXzUuT^rS$v5rEJsVCYkj=RfB4z'
    's<n9PO0!<wGCXd!dxfLxa<zfpC8<YXi+I2xPUM!3>Bx#ak<bqMmKo*oDq{3(O)V0kBMfuqfFp^HLO@`=m`WO7UBCMC?~mDe;N9dc'
    'F7EZnyFUsol>=b>Z0GF7-'
    'L@ls0x+8xnEkOsv)%Hv+bJ_X9gl~DW762}ar0ek@=|2A`a`ZEK37H^!yFt>Pn%r674&%2PyOyGY!&7E=e+qi9Oc!Ef5K<a%Dd7yB'
    'Dnx6FXum7UXc4ho_lum{?<L46y53k$o_b=P<x-'
    'lv&QYvM$GOj&^GDRk(0jdnZs)=>Bdb>K>JcWV*bcE#MySkcJ4UMEdTp9qV{=qyn@2nj@L#-'
    'm$oi9ZRyXKPLyP`@77Niy+yrgEu7l#LjDBG{wZ2|aEyNi8p{B}2*_}<8eI;XkKvzsV9sD?pgFme;?e(t#uQ^1FFdv~6Q!jj(=CJu'
    'sRaaKxhp{Sfwj7i`SmaBw2;H;t?a>WH=cWgmY&Uu8oLT!w^Zq+XmIJwmRExgZBL@A@~m3B(#<lDWnPz5I@kZBA<f^Owa=eZlCS;h'
    '<pni<oPO7Sb#igBx?S;Q{H09Knw4S~wiUwYKO?p!Z!-'
    'OoiQ_rmY?Z(Y!eiQzMW>~iSOo$kgiv0{oMc$TyLZjoOz`fc_?4$k9UD>adN=WBMJxx@*;{jxZnh+>SKMz~bH7#9{fnu)e`4kRo@('
    '!(x%$4Z{(fi${*S7`-(wa2&UN^MD)EQa;&)c#53I)@RFVHfYVrqF<-'
    'feT{Qs`X{Fhvt|I(`SUs8SkORms=nKk+^yGsAX)ah?3^@rB#_gCu=tJfb~vA<=_eqYu8;JW?x%Kg%&<<D2W|J=3p2lIo@*IS*f77'
    'LA!gSBIyKT8$<vnQKADV6l;8PU5{<CnG*eEy33j>`Dwu265U#NMqIyR91gdFruCr#Uw@*;SocI_@Xa*rRT{i8I?}yR8k~*zQb{4e'
    'fMjwxQj*O}b&{<MDM98?^_2_I$)7BhXIwlUZmFKUZpDmO5z1!*U1h*+Go$;b+bsv<IIa^Ox&xe{#^m_1pO^W*s%F>4Emuq4|M!I4'
    'D8T9_`2ww4p(b?P}Am9k6qImH?MF9B8lImu<hc|Gqc#CX`d0_8F9QpR$m$T~^uo4d+Weoh~<5<XdHM=C_-'
    '=_jZ4C(qn!dA+zQnW&G77?N@4YOBYWU^+tSm*^jkfEs5A>s&Axb*Zro4ptqYOWN)@Fa4XTX%OO*MYd0)eeLw@YS54kuS(?AHG=F7'
    'j{>sw)m8JPBOY^75(zGvUX_{TmcVPxk`>>46{>%<S(e|Weqy27w)}}pc!)T}d8}I4*xu0H*)3;wa3C-'
    'KH7<~?z_Wxqs+j8+Ff7EVzFx%indos?d`SYsN8;>?&jm6vTPA9yZo-IMxC6*f=&3=uT%`uiFY<2Pl7(d2sG3ICJ`<-'
    '<DLSFipYbOmIj`C;ydF@^q(XhW6vTXlarcyZlqBZ?uc)Z56;|+{hKLUJr2+#|ojSPlL!b*r#p_791<)~t^E;}^q@U>n9v;Y3jh6B'
    'deas{7~DY$K(;5J!;ojHOpCPQ#P`GL=y9XK#I@JD3^?wS`k*j^lx6F4X%u*(=6oDDcU7jVy+fP2jY9F_&RMGoLoG5`n1{|7|>w~G'
    'C58~Gm;_y2Wf!e3`5{B>r+UuP!#b!Ng}XC{31GZXel__vMk_n)$GU~GT8$o|l{{<cy5En@mliRcfF@$L}K?>rBdbE(u9vW<4uMY6'
    'V=z*i+b?RtliH!4N|7YIFkJpN=hMVeaUIfNAcsU{(9bqFIQ(>4Vi%w#?&JTs(!VY=GJ{RGRy4IiwDEk($e{Xu8+_G<d|gv!?SudV'
    '(dmqDuw(MLdom#LlKp*+9jm|D$hwBGTR;hr=L?Ckf{9p{oy``z&s?a_L$G#_wbObx#H=j2#g(#j@)`3nC&zRvT1fAFte6J>69gDs'
    ';&rZ<@jL7S!xPj1x6Y?mgX#1E^VH0=D>+Vqd<-L9eg@4-'
    'L1W+|&NodWh?`xvkXOVFB*O`avQ9!y3tSr0G7xncrFwMdhdRltr_B%gVf_`nDUp$W0l3@IRG#Qz9Jy+!=JX<pQW+31O{HXa52;NA'
    'z2O@!t3{lD6Qv&{CeSO+@$zQQiS>*Gx=bdgKpJ?)y-'
    '#XCB|I>cNRV3@Ev_z2*b6IVI}Yqqd>HqUGalkato+7>3?{pAiE$hL>c%1V`rm3RSQ0h=pllIFx0Z6pn1cQN8Mxrzqw=_=5ID~egH'
    'w=*IewwcM3p=>)?e6OS6wy^l_uXfN>xjhVyP9iw)2!sJ!LkR`X4V=b#X(n0JG(<;e(zy_!u8BdM7z1vnQ>H`Yn;ATvEVqNh_d3vS'
    '3y1IiZnqkZw};ai@H-2Kr;uqZu|k_1;$V@5$lm%aK?L5-glwhs+^B@V6CAwHhG(^z)3fn-yNvREj^*3J>bt+(?dJRKbANCM2rD&g'
    '6BCFMB4Qz4WZ`&KOE3zNoT{Lh_B<k(&^iJ@MMM_~HZ|MLyq?VX+rjC3oh-'
    'P`{oVQ1whk%W!&F%U6w`XV5RKZ)R(U+%BnKU;G8L$BO%9Jh#91iwpF^>QCjR(nwN9UiDeiSV;ufaf{nd6(THMFjBw+=WiLg~Edu2'
    '22Ircd#6<NXhdQ}PMd(X2btEmM1zbnbO!W8pcZ166daIf<nw=fk<#BXX&aE>M7F3ci=a~uW%Ysow;ux-jYc%EfY6lF3Gl_RQQl*!'
    'u3)CF+Ai?y(f(O7P&Wg9loAuMbZCBcaT62a<osH2oL(nb0TSgE2X;0kH7Hf{lFVh3mMbw=eD&W4DH(zwv9Mh#T4h7tirz&<JbNu#'
    '3z%?s!7m&RE@K}3nAW=kE{Rc+$zWd7eCb_U=+SQl7qQ@9dL>Ik|G0(&fJ1uWE-Kp_`QGlw8RL7jNP>cTDHo45t@3bxCr-sh;zZ4A'
    'BhdmS{mb02qYfL+lIE>V=NO9A1&5#&1|v{AcMDgjP_Mvb>)@GXGprYf4a=FJwcI~m@&gTwbaGjtn=@BD6uP9NRF<N{xql%m}a3DJ'
    '`Bp~k9U+&yFkR#@0j$dMR?N?809;0j=PBbW%7uk~go?-Jm99eKKiy?1}B14pRt<!*MeG9b<%Gff4>TIy=5blMo`t299R7HY+-'
    '!q2j&1qQHZ_&h_Nw%A*@gT41UxOE%6g=a!I2!Vvi18~zRijqdpH?<g93e0!%bj+Ph43JFX-'
    'y}idnDO3zLnN_~3EIU^>NlWWPdQ|#5>&F1w*pRy3cx*^1mA5eu)Cv4U;)NH#$Z^^1wH`?Xfr>j!%cTE^<GENZp$$wf;K<|MRVX{A'
    'Ps_Yunc%GxW^?!;2miysn+D46Qewa)(U<VwyJDnYWqNE2Cf?F<pPwV{1YRn6B7p<5kk~x;9vns0t}NPFJ2%omgcrH5(Pl%Z3&!ap'
    'zRLc-s>FQZK3a--)-MS-aX7s5K20!HA35AU@G^JP)P|RKp%!MFb1J29YG_D<(^J})Y3q-'
    'oTfss_*B8Y*I~e0n0xnk+do!#Uors^LjgE9fd7cE;kbna9ubluB#>I_5@78(9om>Or{RN)BVfotn#2J4iLuzd4m93oYw!GO+h!yG'
    '>5##_4rKnrkimSw^F9+G=|t+Fsj{E(inU}cBbHQ0Nz*Ffe&H`8V}(k-'
    'AcSQgq7*A20X+AXe8F_c^A4`w>ul+5Cg9HRcEFVCJ^ZD8>@4BsBwQuSHu-E}>p6f9%zE((uUi4XGy-auluVU1^#S-'
    '9ZD~&SX8$#vwY)2NaGw*hw<Qnm{BpM&wf(2F4fi_V`;WE_%k#qb`7-CIm6sY+0-'
    '|dVoL4o?t)p6Z4pLWPl8i1mGVlnWfLQ=ql!U>TnSHt(-|MXMZQkt8FSm2(`Mx~3iGnhSssy-'
    '3t+2ugcZRD0I!binHo&(+$nBH$id)*94*)0v%qNqQZ)5Fb@c9nb-'
    's`~iZJF>pzufI6xBvOz!+nm6|HDkg#_{v}OoRnAb%5Q`$rJ&R;JV>WRH9fDq85{J7WlAILC+_H@JG-'
    'omWWRaD|$=baW;Z}7vPl&zTIcVY?3uogpUYV6g*5)g`5{q0F{O2MuE^*a&CpytQni2%|YSB-Cu4Oi+>yUN99i_ya>?>Qyg(vmDwT'
    'u@-hl*5R?)#y^1mzfCWv>5Omav5*Z}H;p-'
    'etVBvpCc&QG#pPh~}ry06O#y=;J!QhvZ@^DK+nrKpiS}I#rtu=YtZ^Nm9SHKZuwWZdpn9)w{>Ra&HRSp~`4D4=}6$Vu&O_M$Z^E^'
    'b_*kY_rA*&iBqKP2rkRw><AQ0Id<k9Z_Zkw5UV8epKo56!t!8oJcN})Px%9b2x&_r2)Gks(pie$J#xI{?~mLsK^xG5`bxi>CNIwA'
    'Nj)@G}P2^{cC3Z|&z7xF6{0?`6T0}gs$0lT8+lwUyP3eamLVh<Yb>*_(Z-Mhcr+6dw@8g<J-'
    ';Tj78azR}e5h1wcJ|>4J;<ezuo%9;2X<^=21BdV>)Rj4xC}T}6=v!>8rPl9VCf26HX#kBwfE!FL)tUss$q6W_R_l_k3FshJ3iLC$'
    '0N4<m0(OW&CVNm$;qLFYov{ZyF`?lsxUN7cHba4^10^RF3?;y$sEW=*st89B?knnt$BPO9mjW_`tYtHUjn$L`*<}=%PK?J`>C`HN'
    'a1_CmK*%60kXWE$<l0gF6ELCz9^g}#iTi+2*06MtQMmiNtupw1XZW_;IRJOX2p5U?$R^-'
    'DQ(coKhN@NxKUx5Y3_Ji!DKy%ng{vWIN`%6g(nOOj20=F%cbD8c7`gzXSEY>_`tFo1CKUMZM9Ee!98I#JY=D%Wxe#DAl&ZXD%HFu'
    'c(zthhw{2`~->#pO!<-wFXdt;O#i~rl@hR^BeGDvKW=-iMCb-QUsA($(piNE3n=wF@E%`<#sf|$XYW@&ZOBZlo0{GrB0j$71J-'
    '~}(Xq!u+ndNwhreo6rM6Bs|ECHSpj5>tLcYn9Vjmbav{<EzqosuH=-'
    'IRX+gK=VWjvQSuYZ+hbOec6@rL+i#m7qqZ;HppvT4jkf!34O(=}1K&4x162!E;$5ER3ti--S;*)0?e|vqQHx_N7lJIc=Te)NG)Bj'
    'Vn4=<?)6|Pz~ps^XnWm<z}M8d>j3AkM5i?TleL*nn_C&l8={8!Ebgjtaka+@FkE&Oefk}(=k=c`|p<z(nG)xd|whiC@I5SdtV#}J'
    '_A!z0uG=&L{L;07jR<+@gAcC{Uo!n42~^ts#rY0{QO6G^!>A^f4}N=KRo{U=9kAW-aSqE^yJA!`(621R>r!4>~1Gb&B9^>`t|j<V'
    'd<#O!r(h;7a9wQcD#a0@+xBnTGv#0@=QeFcc#cHAxiSrAryse)I~sS($ZL<`6-2eww>=}GwwL-'
    '{%SUh=4%p+r6Gt}5N*J2DMX;fNZk)IWCK}3JQ<kP2uHG_JXN%uoGI;)3K4D`7HVboWXfr2$x^@9D+%D_#mYPsh;C3?!!H?xkOsb3'
    '78C1+z4Vg_Q4>ibh`=e&vU29-'
    '(+L22)4%Sd=_d8&^C>Von$BQ}?LE&N(G6ZTJ4lv>x9t7lcxmKIY=aM5ezL@^es4IwDuXxues{RpY<S4E{bQ}hjH=d%$T}K@!SZ0T'
    'MU#O@8GspK6@ofbh4CWD;u&pKG{7ayR>XABKvg5+YwHWBb1D}_Ko=qRc9Rj_cd!2b;@#0j_u1E@S0DQ7r_ST@{_%^Sp1d4h{34$%'
    'b8^a$^L(o_4(Nn~$@cAK=9AM}R5;-'
    'z!gi^ESu3Y@ZPGG$7N0A<Cr}YAq>GD3Crkx@>11SiWRKWAo1D$N(5X{Q!rXCTilu5h2Wm+{R$a7=HoBS-FC*Zjo!qpwE$if+s`$K'
    'WyU;vAvxCQ%o!*MwH9+ZfC@hd);F-46W(58LlFlT8Q_2Hc>cB3NEQkl>2Vg|#jjy?>+OOSzg-`6r9eqvsV0o3-'
    'rohY>ctOhNYdf97wy?0<W^WxZ{cPig%0=21rXManbAAN$>ziX47I5xd(VNjkH=i$QdTV}C!-'
    'hk4_F4c^_Bj>n=kE+Z$I;Xq6q+5`SMP1)Z#pktKJL6YJwG4*@cwUqXXCTq+rM7^<HhD<lc$p+w{cCGrOwSyw2upGH?@IxokrQ(F!'
    'yqm+4nZ_as#tJcHW)zPkWcck9^b_d~E+ZRP~2<{iCy&XGg!ZXLEFyQ(!yAvzZF}*~D{kj?-'
    '=lz3V{NT}(?~zxdYva`DXn?eb&ir;{IFmFpkQ<?F|w_3?N9^oI>Om}b}V&9oeP7n<cYSGVQ)iA#gkwi*KP?(=b)sGCj8&AVBBZ^w'
    'ggVD<a<$(6C=S1;aQcT>ll{%GG^ocv?-;}6gDPv*yy;c`T~W%%KjHI;qe=+1-'
    'Y8tfc@PA8x&4`l17F(n8WHeaueLYr@@Uf^IEww97Jtxj%*VOrl}4e`0zTs;RqBRp~uEihLt?QTI_Un$}#2`U`9lobF)BCw$G>Kb}'
    '%H6U_iQ58#j4lAWzWM0j$PTr{z1)~9v-'
    'P(A7(T7)flcg{xd0`deub``;O??1wl;{nBClpc$#w~ms?Fa@)DGvBf;DeAAGaU9LuMC39<@XCLLyu0LS$vG3U43|8?u6x$PWcJ6^'
    '8vEqGJ;i!m|A5ZU;KbJ+h|RP_yt0iDTLKs&sPX|v2sh%8aD`Y@d(R-vQ3qlOr9>5_Fae+BLQ*zh49ZI_N+-'
    'GNaO+fSp|2S4y(975c+vf;%Ri34uE>S<#?;vQ4+TreAb#CeU!_)StPV*E}ABi>**xh&8Us@I|7gi1;?L4>G7Pt*6hTw-'
    '6lOr*z}aV&jx5cL1qxONI_ClGi4Z|5wJR%s`*r;GZ0L~{Zypmga&t+^l$%m^xKagzCGz&Ts^+};jPp^_RHJvo?GJw!z`ZuW1~r*V'
    ')jjIwk=9s7;oS2UNqKx*)2?1NHP2Mb$gorI=Y^&iwC~d3TOa9ONdGUdr>wkP;dmDk32&Qr3(h|p#<ncPOjM#VW3I2EFQOV^PKm?H'
    'krLaNTd0Ki~&*!Sy!C_L5z(lg`%q$v_HyK1Z)wJ7Sx_u#bk(*n#x4UrPB1vrL!KF9yHa0`EyiuU<@3Ez?)fFIBg=P9i1xC|3%5Lp'
    'mak~6YLlwbS<bCmYP?qS}3?KJZJ4?Dm2tm1EUQl1B${zM=v<;V#sRJ&=M^&)3m1y4~w2`cnF2l4u#_KriaY;F3+DLAbp-'
    'WU|`rS0&PlLOD`n2_Y7vz(^dvVlhl!FG!NCdq+#@eK%iOdrl&xL+H+(%dr-'
    'Co_q7!=m3-w&dq!p`C)iF`jQ|Td&^F|RJd>+*1PX2Hpq6W`a@h8usWi`@6M2w`kChamAP(0|1qnP)qex2j^eRxpjOg44Rx+HCa-P'
    'maq@Md~tlIRP>0vGNXV~oEQ>k<gZ=)fou%J;Jh@7dED+|#UBP$(lL}g6@zu*9%EnbgRD>uH&s?r!v-Dg^u>LpO2&Bf6{Q$+#yFd-'
    '6rjKW9w5A6|+N+!Idg=Z?Ma%ndz&BUC|AHfst90~9W3W8;^1RRb4Qp$5lx&%erBH>Q0i9R^I9>4<pvhr0^7wwiuEH8&Ecz1BYNs%'
    'a9S|yf0f*UC#=H;0Kd*J;L_*cMLor5*Nx<(sLgLmes&Rxg;XshL)a=`F}6%%th&8;tny-OD_BvaN3F9@t4L#2usY*tRYNIkWo3JE'
    '_|Jso@kBO37)P(*;k2hf9YLjYl5J@fQPpsA}qc^5ieI%#HO>E)1l=>6#s>(v2Rj@jupg3mjIYkoA<;=OC2*6iwZ&U9QN%s&26h~z'
    'zNB+yNSqeZkIEoTMbQc6xo>QS3^g7<N9;obzL=V1eBW%t2wvS{T2<(wa6cuu<F0iF0W7*G3MXWn(j=i83^n*DG#^lOS{;FITaLgF'
    '^VC~FTqot$(un+{7qYBAs9>J4tL_#>r{;8Ed&|C_N#^Fhxyzdzp`*&R$qg9=`MLag^k%|E{xa{m0}#rG%A+7~ZQUR^wW`KEpH!^!'
    '!xlgH1_+BE+B<i(Trv-'
    '5ABE>1vy`Qq6>7UrK{E~B3(cNzct^TV^=yUw8BoAm3f95qLg^}?mE2>%U7Hz!74&pue3!ToB`r{V02>$vdk#V4=S!moFR&HGPkLj'
    'd@N9Mm>bk5-r&&NcfC3AD5iiNTu|2L~+S>0sfCQnZ&*#t<1DoK$5Ez>7}NMt-XM!bCe!(vp2XZ$kM`HqM>laJ+Fgp=V!hpCbR{@o'
    'ZM)`R(&NPaa>~hY6uuy_ss(KW7oo(g30FW`W+SzAcTX0Bgnv>)`sZh|N!2QqgUXTT|OZ@4lqfJMKQ$^n-6aGX&diIx`N3-f-'
    'P^Y`gOwdvePMPRz}=XKgV-^T_wX@<Dr_1wQODcZg=cG;7ys%+Q*tV~8^mT-'
    'U54H$2kGJJi2!RLM?RYqF)|auG2IaE?RE7<Y(*Yb?yui3k%@H*@!!yMM-`{uO9Sugi5ed^qWL`|sE7T-'
    '?z2GwmCiIi?MrLzDDtx1Y##ULX+0JAc!8+}KHWb3{Ej2hE|X)NDHKRj1b~rQcsXfIm&^WUsKWlk5K2wQbIfT?#1X?F1VtVK?+%e2'
    '#k9WZuHPv*P|6-KI~S{OSmzxZG$#Z?VXrRhQv79rpzxm!ZIgt3(^Vi*?WlRuPsvI)F<QXbElBV2iob&pM}<Zoh9V^v#5si%%-'
    '8e07H+(8LJsn?yZrAUN%I=2-Z6JRBU8#&(aJ?^=_WBFokvat-mhGU^!S;CR}5uzV}%@u;8r-P8WnHJx`h=gr6AD6d}p6Fz%Z-'
    'j$|<K`tc0_mq+DROQ;;0|Bbey0FP5zv*0+{&;jzQolDT$}OehN8?_vbYD&L=A7o0EVn&P>yMk`Ln$6Hccca4DZUaP&U(U@Ozk1UH'
    'oTjVjk?3r&h^`wjJ<hw(LOo5XmjD(-'
    '<&>gUpzf&lyvbW4*|$){VX>%+KG(bWCB)7p9eUOKY@yWif$e_C!VTf0H6biAi%_0f@9cx46oD!a|XL}9nGbb68SIaNil};!VxGiQ'
    'Cdne-SU;EmaxEEt|0smtkr$YuYXym<@=>3z-'
    'jAX<F7a9=GkN%&PsGN8Hn?#io8@=*;*&Q$vsep;ruyG1ZbbWeE#a$*_*Q`3pt8G30EuKoD;)U@?!#%ubboPA3ZSKI3eMC)K^=seA'
    '6FIrg(3-v`kFOf-T|HX@3M(3wJZY{PJVx`t0}4=ycj_!IsVC*<jG0jN$s{zgE84_@m+EaTgtmGke$L5sdTY<Pb_+cEpw^o9|-R-'
    'ULI_(D7%?3D3<8%--U^E%%2k9|(W91Q;OqfBw(^2N6EK<N'
)


def reference(raw, key, *, bucket=None):
    value = {'key': key, 'versionId': 'synthetic-version-for-test', 'sha256': m.sha(raw), 'bytes': len(raw)}
    return value if bucket is None else value | {'bucket': bucket}


def child(value, path='/synthetic/public/event.json'):
    raw = m.encoded(value)
    return {'rawUtf8': raw.decode(), 'reference': {'path': path, 'sha256': m.sha(raw), 'bytes': len(raw)}}


def historical_children():
    return json.loads(zlib.decompress(base64.b85decode(HISTORICAL_CHILDREN)))


def future_fixture():
    """Real historical proof, synthetic future API/CloudTrail/retirement only."""
    values = historical_children(); old = m.child(values['snapshotAvailable'])
    origin = m.child(values['downsize'])['rds']
    snapshot = {'snapshot': {'DBSnapshotIdentifier': old['snapshotIdentifier'], 'DBSnapshotArn': old['snapshotArn'],
        'DBInstanceIdentifier': origin['identifier'], 'DbiResourceId': origin['resourceId'],
        'SnapshotCreateTime': old['snapshotCreatedAt'], 'Status': 'available', 'SnapshotType': 'manual',
        'Engine': 'mysql', 'EngineVersion': '8.4.11', 'AllocatedStorage': 100, 'StorageType': 'gp3', 'Encrypted': True,
        'KmsKeyId': 'arn:aws:kms:ap-northeast-2:942632789808:key/00000000-1111-2222-3333-444444444444'},
        'tags': [{'Key': k, 'Value': v} for k, v in sorted(old['tags'].items())],
        'attributes': [{'AttributeName': 'restore', 'AttributeValues': []}]}
    source = m.make_source(values, snapshot)
    # Deliberately later than historical source expiry, with its own explicit window.
    started = 1800000000; run = 'lab-test-snapshot-2'; fence = 201
    version = 'synthetic-empty-version'; state_hash = 'e' * 64
    clean = {'schemaVersion': 1, 'status': 'clean', 'dnsMode': 'direct-only', 'runId': 'lab-test-retired',
        'resourceFencingToken': 200, 'ociAuthority': {'status': 'verified'},
        'orphanScan': {'status': 'clean', 'scope': 'global', 'runId': 'lab-test-retired'},
        'terraformState': {'key': 'airbob/lab/terraform.tfstate', 'versionId': version,
            'versionIdSha256': m.sha(version.encode()), 'objectSha256': state_hash, 'resourceCount': 0},
        'completedAt': dt.datetime.fromtimestamp(started-30, dt.timezone.utc).isoformat()}
    clean_key = 'measurements/state-clean/' + m.sha(version.encode()) + '.json'
    retirement = {'rawUtf8': m.encoded(clean).decode(), 'reference': reference(m.encoded(clean), clean_key, bucket=m.service.EVIDENCE)}
    operation = {'schemaVersion': 1, 'kind': m.RESTORE_KIND + '-operation', 'operationId': 'snapshot-test-02',
        'runId': run, 'resourceFence': fence, 'executionCommit': 'a' * 40,
        'sourceProvenance': reference(m.encoded(source), 'datasets/' + m.DATASET + '-mac-snapshots/source.json'),
        'retirementReference': {k: v for k, v in retirement['reference'].items() if k != 'bucket'},
        'emptyState': {'key': 'airbob/lab/terraform.tfstate', 'versionId': version, 'sha256': state_hash},
        'window': {'startedAtEpoch': started, 'expiresAt': started+20000, 'approvedDeadlineEpoch': started+24000},
        'targetIdentifier': 'airbob-' + run}
    target = {'DBInstanceIdentifier': operation['targetIdentifier'], 'DbiResourceId': 'db-NEWPHYSICALSNAPSHOTTARGET123',
        'DBInstanceStatus': 'available', 'Engine': 'mysql', 'EngineVersion': '8.4.11', 'DBInstanceClass': 'db.t3.small',
        'AllocatedStorage': 100, 'StorageType': 'gp3', 'StorageEncrypted': True, 'KmsKeyId': snapshot['snapshot']['KmsKeyId'],
        'MultiAZ': False, 'PubliclyAccessible': False, 'PendingModifiedValues': {},
        'Endpoint': {'Address': 'airbob-lab-test-snapshot-2.test123.ap-northeast-2.rds.amazonaws.com', 'Port': 3306},
        'MasterUserSecret': {'SecretStatus': 'active', 'SecretArn': 'arn:aws:secretsmanager:ap-northeast-2:942632789808:secret:rds!db-test-Ab1234'},
        'InstanceCreateTime': dt.datetime.fromtimestamp(started+10, dt.timezone.utc).isoformat(),
        'BackupRetentionPeriod': 1, 'MasterUsername': 'admin',
        'TagList': [{'Key': k, 'Value': v} for k, v in {'Project': 'airbob', 'Environment': 'performance-lab', 'Service': 'rds',
            'RunId': run, 'FencingToken': str(fence), 'ExpiresAt': str(operation['window']['expiresAt'])}.items()]}
    event = child({'eventID': '00000000-1111-2222-3333-444444444444', 'eventName': 'RestoreDBInstanceFromDBSnapshot',
        'eventSource': 'rds.amazonaws.com', 'recipientAccountId': m.service.ACCOUNT, 'awsRegion': m.service.REGION,
        'eventTime': dt.datetime.fromtimestamp(started+5, dt.timezone.utc).isoformat(),
        'requestParameters': {'dBInstanceIdentifier': target['DBInstanceIdentifier'], 'dBSnapshotIdentifier': source['snapshot']['arn']}})
    args = {'op': operation, 'source': source, 'retirement': retirement, 'snapshot_observation': snapshot,
            'target_api': {'DBInstances': [target]}, 'event': event, 'observed_at': started+600}
    return args


def synthetic_counts(source, restored, *, uuid='11111111-2222-3333-4444-555555555555'):
    """Receipt schema fixture, not a claim that SQL ran against the future target."""
    identity = {'mysqlVersion': '8.4.11', 'serverUuid': uuid, 'schemaName': 'airbobdb', 'tlsCipher': 'TLS_AES_256_GCM_SHA384'}
    return {'schemaVersion': 1, 'kind': m.COUNTS_KIND, 'state': 'TARGET_32_COUNTS_AND_DDL_VERIFIED',
        'sourceSha256': m.digest(source), 'validatorSha256': m.source_sha(), 'restoreCanonicalSha256': m.digest(restored),
        'target': restored['target'], 'runId': restored['operation']['runId'], 'resourceFence': restored['operation']['resourceFence'],
        'window': restored['operation']['window'], 'startedAtEpoch': restored['availableObservedAtEpoch']+1,
        'completedAtEpoch': restored['availableObservedAtEpoch']+200, 'tables': m.validate_source(source)['expectedTables'],
        'sourceServerUuid': source['source']['rds']['serverUuid'], 'identityBefore': identity, 'identityAfter': copy.deepcopy(identity),
        'observedTargetUuid': uuid, 'fullDatasetValidated': False, 'rowContentHashesVerified': False, 'sqlReplayed': False}


class BatchDB:
    def __init__(self, tables):
        self.ddls = {name: 'CREATE TABLE `' + name + '` (\n  `id` bigint NOT NULL\n) ENGINE=InnoDB' for name in tables}
        self.expected = {name: {'rows': i, 'ddlSha256': m.sha(self.ddls[name].encode())} for i, name in enumerate(tables)}
        self.calls = []; self.tls = 'TLS_AES_256_GCM_SHA384'; self.uuid = '11111111-2222-3333-4444-555555555555'
        self.next_uuid = None; self.identity_calls = 0

    def execute(self, sql):
        self.calls.append(sql)
        if sql.startswith('SELECT @@version'):
            self.identity_calls += 1
            uuid = self.next_uuid if self.identity_calls > 1 and self.next_uuid else self.uuid
            return 'mysqlVersion\tserverUuid\tschemaName\n8.4.11\t' + uuid + '\tairbobdb\n'
        if sql.startswith('SHOW SESSION'): return 'Variable_name\tValue\nSsl_cipher\t' + self.tls + '\n'
        if 'information_schema.tables' in sql: return 'tableName\n' + '\n'.join(self.expected) + '\n'
        name = sql.split('`airbobdb`.`')[1].split('`')[0]
        if sql.startswith('SELECT COUNT(*)'): return 'rowCount\n' + str(self.expected[name]['rows']) + '\n'
        if sql.startswith('SHOW CREATE TABLE'): return 'Table\tCreate Table\n' + name + '\t' + self.ddls[name] + '\n'
        raise AssertionError('Unexpected query family')


class MacSnapshot(unittest.TestCase):
    def setUp(self):
        self.args = future_fixture(); self.source = self.args['source']
        self.restored = m.validate_restore(**self.args)
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup); self.root = Path(self.temp.name).resolve()

    def test_actual_source_raw_children_and_original_32_expectations(self):
        origin_paths = {x['reference']['path'] for x in self.source['children'].values()}
        read_bytes = Path.read_bytes
        def bounded_read(path):
            self.assertNotIn(str(path), origin_paths, 'Origin path must never be reopened')
            return read_bytes(path)
        with patch.object(Path, 'read_bytes', bounded_read): proof = m.validate_source(self.source)
        self.assertEqual(32, len(proof['expectedTables']))
        self.assertEqual(160882380, sum(x['rows'] for x in proof['expectedTables'].values()))
        self.assertEqual('8b6bb956ff7642bcdbae4a33152e20f8718893f2c4628109a01a004f9baff6e4', self.source['children']['countsDdl']['reference']['sha256'])
        self.assertFalse(self.source['fullDatasetValidated'])

    def test_child_raw_bytes_kind_path_and_exact_hash_reject(self):
        for key in m.CHILDREN:
            value = copy.deepcopy(self.source); value['children'][key]['rawUtf8'] += ' '
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'SOURCE_CHILD_BYTES_CHANGED'): m.validate_source(value)
        for path in ('relative.json', '/tmp/../proof.json'):
            value = copy.deepcopy(self.source); value['children']['sqlImport']['reference']['path'] = path
            with self.subTest(path=path), self.assertRaises(ValueError): m.validate_source(value)
        value = copy.deepcopy(self.source); raw = m.child(value['children']['sqlImport']); raw['kind'] = 'foreign'
        value['children']['sqlImport'] = child(raw)
        with self.assertRaises(ValueError): m.validate_source(value)

    def test_snapshot_intent_or_persistence_and_source_scope_changed(self):
        for change in ('tag', 'request', 'scope', 'sharing', 'source'):
            args = copy.deepcopy(self.args)
            if change == 'tag': args['snapshot_observation']['tags'][0]['Value'] = 'foreign'
            if change == 'request':
                raw = m.child(args['source']['children']['snapshotIntent']); raw['requestSha256'] = 'f' * 64
                args['source']['children']['snapshotIntent'] = child(raw)
            if change == 'scope': args['source']['fullDatasetValidated'] = True
            if change == 'sharing': args['snapshot_observation']['attributes'][0]['AttributeValues'] = ['all']
            if change == 'source': args['snapshot_observation']['snapshot']['DbiResourceId'] = 'db-FOREIGN'
            with self.subTest(change=change), self.assertRaises(ValueError): m.validate_restore(**args)

    def test_expired_historical_source_is_not_a_target_ttl(self):
        self.assertGreater(self.args['op']['window']['startedAtEpoch'], self.source['source']['expiresAt'])
        self.assertEqual('MAC_SNAPSHOT_RDS_AVAILABLE', self.restored['state'])
        for key, value in [('expiresAt', self.args['op']['window']['approvedDeadlineEpoch']+1), ('approvedDeadlineEpoch', 1)]:
            args = copy.deepcopy(self.args); args['op']['window'][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): m.validate_restore(**args)

    def test_explicit_future_168_hour_window_is_bounded_without_extending_source(self):
        args = copy.deepcopy(self.args); old_source_expiry = args['source']['source']['expiresAt']
        window = args['op']['window']; window['expiresAt'] = window['approvedDeadlineEpoch'] = window['startedAtEpoch']+168*3600
        next(x for x in args['target_api']['DBInstances'][0]['TagList'] if x['Key']=='ExpiresAt')['Value'] = str(window['expiresAt'])
        result = m.validate_restore(**args)
        self.assertEqual(168*3600, result['operation']['window']['expiresAt']-window['startedAtEpoch'])
        self.assertEqual(old_source_expiry, args['source']['source']['expiresAt'])
        window['expiresAt'] += 1; window['approvedDeadlineEpoch'] += 1
        with self.assertRaises(ValueError): m.validate_restore(**args)

    def test_existing_target_or_wrong_rds_shape_rejected(self):
        old = self.source['source']['rds']
        mutations = [('DbiResourceId', old['resourceId']), ('DBInstanceIdentifier', old['identifier']),
                     ('DBInstanceClass', 'db.m6i.large'), ('AllocatedStorage', 20), ('StorageType', 'gp2'),
                     ('PubliclyAccessible', True), ('MultiAZ', True), ('PendingModifiedValues', {'DBInstanceClass': 'db.m6i.large'})]
        for key, value in mutations:
            args = copy.deepcopy(self.args); args['target_api']['DBInstances'][0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): m.validate_restore(**args)
        args = copy.deepcopy(self.args); args['target_api']['DBInstances'].append(copy.deepcopy(args['target_api']['DBInstances'][0]))
        with self.assertRaises(ValueError): m.validate_restore(**args)

    def test_target_tags_and_new_fence_are_exact(self):
        for name in ('RunId', 'FencingToken', 'ExpiresAt'):
            args = copy.deepcopy(self.args)
            next(x for x in args['target_api']['DBInstances'][0]['TagList'] if x['Key'] == name)['Value'] = 'foreign'
            with self.subTest(name=name), self.assertRaises(ValueError): m.validate_restore(**args)
        args = copy.deepcopy(self.args); args['op']['resourceFence'] = 76
        with self.assertRaises(ValueError): m.validate_restore(**args)

    def test_missing_or_foreign_actual_restore_event_is_rejected_on_both_paths(self):
        for field, value in [('eventName', 'CreateDBInstance'), ('awsRegion', 'us-east-1'), ('recipientAccountId', '000000000000'),
                             ('errorCode', 'AccessDenied'), ('eventID', ''), ('eventTime', '2020-01-01T00:00:00Z')]:
            event = m.child(self.args['event']); event[field] = value
            args = copy.deepcopy(self.args); args['event'] = child(event)
            with self.subTest(field=field), self.assertRaises(ValueError): m.validate_restore(**args)
            restored = copy.deepcopy(self.restored); restored['event'] = args['event']
            with self.subTest(field=field, receipt=True), self.assertRaises(ValueError): m.validate_restore_receipt(restored, self.source)
        event = m.child(self.args['event']); event['requestParameters']['dBSnapshotIdentifier'] = 'foreign'
        args = copy.deepcopy(self.args); args['event'] = child(event)
        with self.assertRaises(ValueError): m.validate_restore(**args)

    def test_retirement_empty_version_and_orphan_proof_are_required(self):
        for name in ('nonempty', 'unverified', 'version', 'hash'):
            args = copy.deepcopy(self.args); clean = m.child(args['retirement'])
            if name == 'nonempty': clean['terraformState']['resourceCount'] = 1
            if name == 'unverified': clean['orphanScan']['status'] = 'unknown'
            if name == 'version': args['op']['emptyState']['versionId'] = 'foreign-version'
            if name == 'hash': args['op']['sourceProvenance']['sha256'] = 'f' * 64
            if name in ('nonempty', 'unverified'):
                raw = m.encoded(clean); args['retirement']['rawUtf8'] = raw.decode()
                args['retirement']['reference'].update(sha256=m.sha(raw), bytes=len(raw))
                args['op']['retirementReference'] = {k: v for k, v in args['retirement']['reference'].items() if k != 'bucket'}
            with self.subTest(name=name), self.assertRaises(ValueError): m.validate_restore(**args)

    def test_restore_receipt_cannot_change_time_target_or_claims(self):
        for key, value in [('timerSemantics', 'measured-app-ready'), ('requestToAvailableSeconds', 1), ('fullDatasetValidated', True),
                           ('sqlReplayed', True), ('availableObservedAtEpoch', self.args['op']['window']['expiresAt'])]:
            result = copy.deepcopy(self.restored); result[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): m.validate_restore_receipt(result, self.source)

    def test_new_target_counts_receipt_accepts_actual_observed_uuid_including_coincidence(self):
        for uuid in ('11111111-2222-3333-4444-555555555555', self.source['source']['rds']['serverUuid']):
            counts = synthetic_counts(self.source, self.restored, uuid=uuid)
            target = m.make_target_receipt(self.source, self.restored, counts)
            self.assertEqual(uuid, target['target']['serverUuid'])
            self.assertFalse(target['fullDatasetValidated']); self.assertFalse(target['rowContentHashesVerified'])

    def test_wrong_count_ddl_uuid_or_incomplete_scope_cannot_admit(self):
        for change in ('count', 'ddl', 'uuid', 'tls', 'missing', 'extra', 'foreignSource', 'late'):
            counts = synthetic_counts(self.source, self.restored)
            first = next(iter(counts['tables']))
            if change == 'count': counts['tables'][first]['rows'] += 1
            if change == 'ddl': counts['tables'][first]['ddlSha256'] = 'f' * 64
            if change == 'uuid': counts['identityAfter']['serverUuid'] = self.source['source']['rds']['serverUuid']
            if change == 'tls': counts['identityBefore']['tlsCipher'] = ''; counts['identityAfter']['tlsCipher'] = ''
            if change == 'missing': del counts['tables'][first]
            if change == 'extra': counts['fullPreparedDatasetVerified'] = True
            if change == 'foreignSource': counts['validatorSha256'] = 'f' * 64
            if change == 'late': counts['completedAtEpoch'] = counts['window']['expiresAt']
            with self.subTest(change=change), self.assertRaises(ValueError): m.make_target_receipt(self.source, self.restored, counts)

    def test_32_table_loop_uses_original_ddl_bytes_and_only_count_schema_queries(self):
        db = BatchDB(m.validate_source(self.source)['expectedTables']); observed = []; guards = []
        tables, before, after = m._collect_counts(db, db.expected, lambda *args: observed.append(args), lambda: guards.append(True))
        self.assertEqual(db.expected, tables); self.assertEqual(before, after); self.assertEqual(32, len(observed))
        self.assertEqual(70, len(db.calls)); self.assertEqual(71, len(guards))
        self.assertEqual(32, sum(q.startswith('SELECT COUNT(*)') for q in db.calls))
        self.assertEqual(32, sum(q.startswith('SHOW CREATE TABLE') for q in db.calls))
        name = next(iter(db.expected)); ddl = db.ddls[name] + ' AUTO_INCREMENT=23'
        raw = 'Table\tCreate Table\n' + name + '\t' + ddl + '\n'
        self.assertEqual(m.sha(ddl.encode()), m.ddl_sha(raw, name))
        self.assertNotEqual(db.expected[name]['ddlSha256'], m.ddl_sha(raw, name))

    def test_sql_identity_change_tls_and_count_mismatch_close_loop(self):
        for change in ('uuid', 'tls', 'count'):
            db = BatchDB(m.validate_source(self.source)['expectedTables']); expected = copy.deepcopy(db.expected)
            if change == 'uuid': db.next_uuid = self.source['source']['rds']['serverUuid']
            if change == 'tls': db.tls = ''
            if change == 'count': expected[next(iter(expected))]['rows'] += 1
            with self.subTest(change=change), self.assertRaises(ValueError): m._collect_counts(db, expected, lambda *a: None, None)

    def test_actual_sealed_hashes_reject_synthetic_ddl_and_preserve_partial_without_replay(self):
        db = BatchDB(m.validate_source(self.source)['expectedTables']); output = self.root / 'counts'
        with patch.object(m.time, 'time', return_value=self.args['observed_at']+100), self.assertRaisesRegex(ValueError, 'UNCONFIRMED'):
            m.validate_counts(db, self.restored, self.source, output)
        result = json.loads((output / 'result.json').read_bytes())
        self.assertEqual('TARGET_COUNTS_DDL_UNCONFIRMED', result['state']); self.assertFalse(result['sqlReplayed'])
        self.assertFalse(result['fullDatasetValidated']); self.assertEqual(33, len(list(output.glob('*.json'))))
        before = len(db.calls)
        with self.assertRaisesRegex(ValueError, 'NEW_COUNT_OUTPUT_REQUIRED'): m.validate_counts(db, self.restored, self.source, output)
        self.assertEqual(before, len(db.calls))
        self.assertNotIn('CREATE TABLE', (output / 'result.json').read_text())

    def test_batch_shape_rejects_ambiguous_headers_and_ddl_wrong_table(self):
        for raw in ('x\tx\n1\t2\n', 'x\ny\tz\n', 'x\ny'):
            with self.subTest(raw=raw), self.assertRaises(ValueError): m.batch_rows(raw)
        with self.assertRaises(ValueError): m.ddl_sha('Table\tCreate Table\nforeign\tCREATE TABLE `foreign` (id int)\n', 'member')

    def test_package_imports_isolated_without_network_and_requires_real_dependency(self):
        members = m.service.TOOLS + ('growth_b_mac_service.py', 'growth_b_mac_downsize.py', 'growth_b_mac_snapshot.py')
        archive = io.BytesIO()
        with tarfile.open(fileobj=archive, mode='w:gz') as tar:
            for name in members:
                raw = (SCRIPTS / name).read_bytes(); item = tarfile.TarInfo(name); item.size = len(raw); item.mode = 0o600
                tar.addfile(item, io.BytesIO(raw))
        package = self.root / 'package'; package.mkdir()
        with tarfile.open(fileobj=io.BytesIO(archive.getvalue()), mode='r:gz') as tar:
            self.assertEqual(set(members), set(tar.getnames()))
            for item in tar.getmembers():
                raw = tar.extractfile(item).read(); self.assertEqual((SCRIPTS / item.name).read_bytes(), raw)
                (package / item.name).write_bytes(raw)
        code = 'import socket,ssl,sys; socket.socket=lambda *a,**k:(_ for _ in ()).throw(AssertionError("network"));sys.path.insert(0,sys.argv[1]);import growth_b_mac_snapshot;print(growth_b_mac_snapshot.MODE)'
        result = subprocess.run([sys.executable, '-I', '-B', '-c', code, str(package)], capture_output=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr.decode()); self.assertEqual(m.MODE, result.stdout.decode().strip())
        (package / 'growth_b_mac_downsize.py').unlink()
        result = subprocess.run([sys.executable, '-I', '-B', '-c', code, str(package)], capture_output=True, timeout=10)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(list(package.rglob('*.pyc')))

    def new_manifest(self):
        manifest = copy.deepcopy(m.validate_source(self.source)['documents']['serviceManifest'])
        counts = synthetic_counts(self.source, self.restored); target = m.make_target_receipt(self.source, self.restored, counts)
        run = self.restored['operation']['runId']; release = 'synthetic-target-service'
        manifest.update(runId=run, serviceRelease=release, rds={k: target['target'][k] for k in ('identifier','resourceId','serverUuid')})
        manifest['cdc'] = m.service.cdc_identity(run, target['target']['serverUuid'])
        for field, name in [('appRuntimeBinding','app-runtime-binding.json'),('consumerTools','consumer-tools.tar.gz')]:
            manifest[field]['key'] = 'datasets/' + m.DATASET + '-aws-service/' + release + '/files/' + name
        values = {'sourceProvenance': self.source, 'receipt': target, 'restoreReceipt': self.restored, 'countsDdlReceipt': counts}
        prep = {'sourceMode': m.MODE, 'rdsCaBundle': manifest['preparation']['rdsCaBundle']}; objects = {}
        for key, value in values.items():
            object_key = ('datasets/' + m.DATASET + '-mac-snapshots/' if key == 'sourceProvenance' else 'data-bootstrap/' + run + '/') + key + '.json'
            raw = m.encoded(value); prep[key] = reference(raw, object_key); objects[object_key] = raw
        # Keep the actual source-version ref bound by the restore receipt.
        prep['sourceProvenance'] = self.restored['operation']['sourceProvenance']
        objects[prep['sourceProvenance']['key']] = m.encoded(self.source)
        manifest['preparation'] = prep
        manifest['toolSources'] = {name: m.service.sha(SCRIPTS / name) for name in m.service.tools_for(manifest)}
        context = {'runId': run, 'resourceFence': target['resourceFence'], 'expiresAt': str(target['window']['expiresAt']),
            'databaseBootstrap': 'snapshot', 'rdsInstanceClass': 'db.t3.small', 'rds': self.restored['target'],
            'lease': {}, 'manifestSha256': m.digest(manifest), 'redisImage': 'unused-before-runtime', 'debeziumSecretArn': 'unused-before-runtime'}
        return manifest, context, objects

    def test_new_manifest_real_common_validator_and_closed_mode(self):
        manifest, _, _ = self.new_manifest()
        self.assertEqual(manifest, m.service.validate_manifest(manifest, m.DATASET, manifest['runId'], manifest['serviceRelease']))
        self.assertEqual(12, len(m.service.tools_for(manifest)))
        for change in ('old-mode', 'linux-proof', 'missing-counts'):
            value = copy.deepcopy(manifest)
            if change == 'old-mode': value['preparation']['sourceMode'] = m.mac.MODE
            if change == 'linux-proof': value['preparation']['preparedFingerprintSha256'] = 'a' * 64
            if change == 'missing-counts': del value['preparation']['countsDdlReceipt']
            with self.subTest(change=change), self.assertRaises(ValueError): m.service.validate_manifest(value, m.DATASET, value['runId'], value['serviceRelease'])

    def test_bootstrap_uses_actual_source_and_target_before_private_runtime_or_mutation(self):
        for failure in ('class', 'writers', 'ca-version'):
            with self.subTest(failure=failure):
                manifest, context, objects = self.new_manifest(); events = []
                api = copy.deepcopy(self.args['target_api'])
                if failure == 'class': api['DBInstances'][0]['DBInstanceClass'] = 'db.m6i.large'
                class Aws:
                    def call(inner, *args):
                        events.append(args[:2])
                        if args[:2] == ('s3api','get-object'):
                            key = args[args.index('--key')+1]; version = args[args.index('--version-id')+1]
                            if key == manifest['preparation']['rdsCaBundle']['key']:
                                # Raw PEM must go through exact byte/version admission, not JSON fetch.
                                Path(args[-1]).write_bytes(b'-----BEGIN CERTIFICATE-----\nSYNTHETIC\n-----END CERTIFICATE-----\n')
                                return {'VersionId': 'foreign-version'}
                            Path(args[-1]).write_bytes(objects[key]); return {'VersionId': version}
                        if args[:2] == ('sts','get-caller-identity'): return {'Account': m.service.ACCOUNT}
                        if args[:2] == ('rds','describe-db-instances'): return api
                        if args[:2] == ('autoscaling','describe-auto-scaling-groups'):
                            return {'AutoScalingGroups': [{'AutoScalingGroupName': 'airbob-'+manifest['runId']+'-app',
                                'DesiredCapacity': 1 if failure == 'writers' else 0, 'MinSize': 0, 'Instances': []}]}
                        raise AssertionError('Private/runtime/mutation boundary reached')
                with patch.object(m.restore, 'Aws', Aws), patch.object(m.restore, 'Lease', return_value=lambda **k: None), \
                     patch.object(m.time, 'time', return_value=self.args['observed_at']+1000), self.assertRaises(ValueError):
                    m.bootstrap(manifest, context, self.root, self.root / failure)
                self.assertFalse(any(e[0] == 'secretsmanager' for e in events))
                self.assertFalse((self.root / failure / '.connection').exists())

    def test_reused_native_engine_count_and_sample_before_alias_without_full_claim(self):
        from test_growth_b_mac_service import FakeES
        manifest, _, _ = self.new_manifest(); selection = manifest['search']
        transport = {'schemaVersion': 1, 'kind': 'global-growth-b-search-transport', 'datasetId': m.DATASET,
            'snapshotRelease': selection['snapshotRelease'], 'bucket': m.service.BUCKET, 'region': m.service.REGION,
            'repository': {'type': 's3', 'bucket': m.service.BUCKET,
                'basePath': 'datasets/'+m.DATASET+'-search/'+selection['snapshotRelease']+'/native'},
            'source': {'appJarSha256': manifest['application']['appJarSha256']}, 'sql': {'dump': {'sha256': m.DUMP_SHA}},
            'snapshotUuid': 'synthetic-native-snapshot'}
        es = FakeES(manifest, transport); output = self.root / 'native'; output.mkdir()
        result = m.mac.native_restore(manifest, transport, m.DUMP_SHA, es, lambda **kw: None, output, 60)
        paths = [x[1] for x in es.calls]
        self.assertLess(paths.index('/'+result['restoredIndex']+'/_search'), paths.index('/_aliases'))
        self.assertEqual(657358, result['documents']); self.assertFalse(result['allDocumentSourceFieldsEqual'])
        self.assertFalse(result['fullDatasetValidated']); self.assertFalse(result['sqlReplayed'])

    def test_python39_syntax(self):
        ast.parse((SCRIPTS / 'growth_b_mac_snapshot.py').read_text(), feature_version=(3,9))


if __name__ == '__main__': unittest.main()
