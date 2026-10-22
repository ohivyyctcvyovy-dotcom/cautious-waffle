# MapaForge RPG

Editor de mapas 2D para Android, feito como aplicativo web instalável.

## Teste
Abra `index.html` em um navegador. Para instalar como PWA e usar offline, publique os arquivos em um endereço HTTPS, abra no Chrome Android e escolha no menu **Instalar app** ou **Adicionar à tela inicial**. O modo PWA não instala a partir de `file://`.

## Recursos
- Mapa top-down com grade e tiles 16x16 ou 32x32.
- Pincel, borracha, preenchimento e conta-gotas.
- Tiles demonstrativos, importação de PNG/JPEG/WebP e biblioteca de sprites.
- Camadas, redimensionamento, marcação visual de colisões.
- Desfazer/refazer, salvamento local, importação/exportação JSON e exportação PNG.
- Limites: 160x160 células, 8 camadas, 150 sprites.

É um protótipo inicial. Para projetos grandes, exporte JSON com frequência. Colisões são marcações por célula e ainda não são exportadas como arquivo separado.


## Redesign mobile
A interface agora prioriza a área de edição, com ferramentas em barra flutuante lateral, biblioteca de sprites na faixa inferior e configurações em painel acessado pelo botão ⚙. O botão ⛶ solicita tela cheia quando suportado pelo navegador.
