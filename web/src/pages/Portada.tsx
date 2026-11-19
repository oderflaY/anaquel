import { Enlace } from '../router'

export default function Portada({ conSesion }: { conSesion: boolean }) {
  return (
    <>
      <section className="hero">
        <h1>Una foto al día y tu tienda sabe qué le falta.</h1>
        <p className="lead">
          Anaquel compara la foto de tu anaquel con la de cuando estaba lleno, te dice qué se está acabando,
          le manda el pedido a tu proveedor por WhatsApp y te sugiere productores de tu zona.
        </p>
        <div className="fila">
          <Enlace href={conSesion ? '/panel' : '/entrar'} className="boton">{conSesion ? 'Ir a mi panel' : 'Registrar mi tienda'}</Enlace>
          <Enlace href="/entrar?demo=1" className="boton claro">Ver la demo</Enlace>
          <Enlace href="/productores" className="boton claro">Soy productor</Enlace>
        </div>
      </section>

      <section className="rejilla" style={{ marginBottom: '2rem' }}>
        <div className="tarjeta reto">
          <h3>1. Qué se está acabando</h3>
          <p className="secundario">Toma la foto al cerrar. Anaquel marca los huecos vacíos o con poco producto. No tienes que anotar cada venta.</p>
        </div>
        <div className="tarjeta reto r2">
          <h3>2. Pedidos en orden</h3>
          <p className="secundario">Arma la lista de resurtido por proveedor y la envía por WhatsApp. Cuando la siguiente foto ve el hueco lleno, el pedido se marca como recibido solo.</p>
        </div>
        <div className="tarjeta reto r3">
          <h3>3. Productores de aquí</h3>
          <p className="secundario">Si falta huevo, queso o tortilla, te sugiere productores de tu zona. Tus vecinos ven en tu página qué productos locales tienes hoy.</p>
        </div>
      </section>

      <section className="tarjeta" style={{ marginBottom: '2rem' }}>
        <h2>Cómo funciona</h2>
        <div className="columna" style={{ marginTop: '1rem' }}>
          <div className="paso"><span className="num">1</span><p>Tomas una foto de tu anaquel lleno y marcas con el dedo dónde va cada producto. Esto se hace una sola vez.</p></div>
          <div className="paso"><span className="num">2</span><p>Cada noche tomas otra foto desde el mismo lugar.</p></div>
          <div className="paso"><span className="num">3</span><p>Anaquel te dice qué falta, a quién pedírselo y te deja el mensaje listo para WhatsApp.</p></div>
        </div>
      </section>
      <footer>Anaquel · Durango, México · Funciona en la web, Android y iPhone.</footer>
    </>
  )
}
